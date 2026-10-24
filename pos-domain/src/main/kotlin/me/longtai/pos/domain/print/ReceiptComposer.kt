package me.longtai.pos.domain.print

import me.longtai.core.common.barcode.BarcodeCheck
import me.longtai.core.common.money.Money
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.common.print.Align
import me.longtai.core.common.print.Cell
import me.longtai.core.common.print.DividerStyle
import me.longtai.core.common.print.FontSize
import me.longtai.core.common.print.HriPosition
import me.longtai.core.common.print.LabelSize
import me.longtai.core.common.print.PrintDocument
import me.longtai.core.common.print.PrintDocumentBuilder
import me.longtai.core.common.print.printDocument
import me.longtai.core.common.time.TimeFormats
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.PaymentMethod
import me.longtai.pos.domain.model.Product
import me.longtai.pos.domain.model.Shift
import me.longtai.pos.domain.model.StoreProfile
import me.longtai.pos.domain.model.TaxMode
import me.longtai.pos.domain.report.CashReconciliation
import me.longtai.pos.domain.report.SalesSummary
import java.time.LocalDate
import java.time.ZoneId

class ReceiptComposer(
    private val money: MoneyFormat,
    private val zone: ZoneId,
) {
    private fun m(value: Money) = money.format(value)

    private fun PrintDocumentBuilder.storeHeader(store: StoreProfile) {
        title(store.name)
        store.address?.let { center(it) }
        store.phone?.let { center("電話 $it") }
        store.taxId?.let { center("統編 $it") }
        divider(DividerStyle.DOUBLE)
    }

    fun receipt(order: Order, store: StoreProfile, reprint: Boolean = false): PrintDocument = printDocument {
        storeHeader(store)
        center("交易明細", FontSize.LARGE, bold = true)
        if (reprint) center("*** 補印 ***", bold = true)
        pair("單號", order.orderNo)
        pair("時間", TimeFormats.dateTime(order.createdAt, zone))
        pair("收銀員", order.operatorName)
        if (order.memberNo != null) pair("會員", "${order.memberNo} ${order.memberName.orEmpty()}".trim())
        divider()
        row(Cell("品名/單價×數量", 2), Cell("金額", 1, Align.RIGHT), bold = true)
        order.lines.forEach { line ->
            text(line.name)
            row(Cell("  ${m(line.unitPrice)} × ${line.quantity}", 2), Cell(m(line.unitPrice * line.quantity), 1, Align.RIGHT))
            if (line.discount.isPositive) row(Cell("  折扣", 2), Cell("-${m(line.discount)}", 1, Align.RIGHT))
        }
        divider()
        pair("商品數量", order.itemCount.toString())
        pair("小計", m(order.gross - order.lineDiscounts))
        if (order.memberDiscount.isPositive) pair("會員折扣", "-${m(order.memberDiscount)}")
        if (order.orderDiscount.isPositive) pair("整單折扣", "-${m(order.orderDiscount)}")
        taxLine(order)
        pair("應收", m(order.total), size = FontSize.LARGE, bold = true)
        divider()
        order.payments.forEach { p ->
            pair(p.method.label, m(p.tendered))
            p.reference?.let { pair("  交易序號", maskReference(it)) }
        }
        if (order.change.isPositive) pair("找零", m(order.change), bold = true)
        if (order.memberNo != null && order.pointsEarned > 0) {
            divider()
            pair("本次累積點數", order.pointsEarned.toString())
        }
        divider()
        store.footer?.takeIf { it.isNotBlank() }?.let { center(it) }
        if (BarcodeCheck.isPrintableCode128(order.orderNo)) {
            feed(8)
            barcode(order.orderNo, heightPx = 64, hri = HriPosition.NONE)
        }
        center("此為交易明細，非統一發票", FontSize.SMALL)
    }

    private fun PrintDocumentBuilder.taxLine(order: Order) {
        when (order.taxMode) {
            TaxMode.INCLUSIVE -> if (order.tax.isPositive) pair("營業稅(內含 ${percent(order.taxRateBp)})", m(order.tax))
            TaxMode.EXCLUSIVE -> pair("營業稅(外加 ${percent(order.taxRateBp)})", m(order.tax))
            TaxMode.NONE -> Unit
        }
    }

    fun refundSlip(order: Order, store: StoreProfile): PrintDocument = printDocument {
        storeHeader(store)
        center("退貨單", FontSize.LARGE, bold = true)
        pair("原單號", order.orderNo)
        pair("原交易", TimeFormats.dateTime(order.createdAt, zone))
        order.refundedAt?.let { pair("退貨時間", TimeFormats.dateTime(it, zone)) }
        order.refundOperatorName?.let { pair("經辦人", it) }
        order.refundReason?.takeIf { it.isNotBlank() }?.let { pair("原因", it) }
        divider()
        order.lines.forEach { line ->
            text(line.name)
            row(Cell("  ${m(line.unitPrice)} × ${line.quantity}", 2), Cell("-${m(line.net)}", 1, Align.RIGHT))
        }
        divider()
        pair("退款合計", "-${m(order.total)}", size = FontSize.LARGE, bold = true)
        order.payments.forEach { p -> pair("  退回${p.method.label}", "-${m(p.amount)}") }
        divider()
        feed(24)
        text("顧客簽名：")
        feed(48)
        divider()
    }

    fun shiftReport(
        store: StoreProfile,
        shift: Shift,
        summary: SalesSummary,
        cash: CashReconciliation,
        closing: Boolean,
        printedAt: Long,
    ): PrintDocument = printDocument {
        title(store.name, FontSize.NORMAL)
        center(if (closing) "交班報表 (Z)" else "班別報表 (X)", FontSize.LARGE, bold = true)
        pair("收銀員", shift.operatorName)
        pair("開班", TimeFormats.dateTimeShort(shift.openedAt, zone))
        shift.closedAt?.let { pair("結班", TimeFormats.dateTimeShort(it, zone)) }
        salesBody(summary)
        divider()
        center("現金盤點", bold = true)
        pair("開班零用金", m(cash.openingCash))
        pair("現金收支(淨)", m(cash.cashIn))
        pair("應有現金", m(cash.expectedCash), bold = true)
        cash.countedCash?.let { pair("實點現金", m(it)) }
        cash.variance?.let { v ->
            val label = when {
                v.isZero -> "差額(相符)"
                v.isNegative -> "差額(短少)"
                else -> "差額(溢收)"
            }
            pair(label, m(v), bold = true)
        }
        footer(printedAt)
    }

    fun dailyReport(store: StoreProfile, date: LocalDate, summary: SalesSummary, printedAt: Long): PrintDocument = printDocument {
        title(store.name, FontSize.NORMAL)
        center("營業日報表", FontSize.LARGE, bold = true)
        pair("營業日", TimeFormats.date(date))
        salesBody(summary)
        footer(printedAt)
    }

    private fun PrintDocumentBuilder.salesBody(summary: SalesSummary) {
        divider()
        pair("交易筆數", summary.orderCount.toString())
        pair("退貨筆數", summary.refundCount.toString())
        pair("銷售商品數", summary.itemCount.toString())
        pair("銷售總額", m(summary.grossSales))
        pair("退貨金額", "-${m(summary.refunds)}")
        pair("淨銷售額", m(summary.netSales), bold = true)
        pair("折扣合計", m(summary.discounts))
        pair("稅額", m(summary.tax))
        pair("平均客單價", m(summary.averageTicket))
        divider()
        center("付款方式", bold = true)
        if (summary.byMethod.isEmpty()) center("（無）")
        PaymentMethod.entries.forEach { method ->
            summary.byMethod[method]?.let { pair(method.label, m(it)) }
        }
        if (summary.topProducts.isNotEmpty()) {
            divider()
            center("熱銷商品", bold = true)
            summary.topProducts.forEachIndexed { index, p ->
                row(Cell("${index + 1}. ${p.name}", 3), Cell("×${p.quantity}", 1, Align.RIGHT), Cell(m(p.amount), 2, Align.RIGHT))
            }
        }
    }

    private fun PrintDocumentBuilder.footer(printedAt: Long) {
        divider()
        text("列印時間 ${TimeFormats.dateTime(printedAt, zone)}", size = FontSize.SMALL)
        text("主管簽核：", size = FontSize.NORMAL)
        feed(48)
        divider()
    }

    /** Shelf / price label for label stock. */
    fun priceLabel(product: Product, label: LabelSize, storeName: String? = null): PrintDocument = printDocument {
        val code = product.barcode?.takeIf { BarcodeCheck.isPrintableCode128(it) } ?: product.sku
        storeName?.takeIf { it.isNotBlank() }?.let { text(it, Align.LEFT, FontSize.SMALL) }
        text(product.name, Align.LEFT, FontSize.NORMAL, bold = true)
        text(m(product.price) + (product.unit?.let { " / $it" } ?: ""), Align.RIGHT, FontSize.XLARGE, bold = true)
        if (BarcodeCheck.isPrintableCode128(code)) {
            val width = (label.widthDots - 32).coerceAtLeast(160)
            barcode(
                code,
                symbology = BarcodeCheck.symbologyFor(code),
                heightPx = (label.heightDots / 4).coerceIn(40, 120),
                widthPx = width,
                hri = HriPosition.BELOW,
                align = Align.CENTER,
            )
        }
    }

    private fun maskReference(ref: String): String = if (ref.length <= 4) ref else "****" + ref.takeLast(4)

    private fun percent(bp: Int): String {
        val whole = bp / 100
        val frac = bp % 100
        return if (frac == 0) "$whole%" else "$whole.${frac.toString().padStart(2, '0').trimEnd('0')}%"
    }
}
