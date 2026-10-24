package me.longtai.pos.domain.io

import me.longtai.core.common.barcode.BarcodeCheck
import me.longtai.core.common.csv.Csv
import me.longtai.core.common.csv.CsvTable
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.common.time.TimeFormats
import me.longtai.pos.domain.model.Member
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.Product
import java.time.ZoneId

data class ImportError(val line: Int, val message: String)

data class ImportResult<T>(val items: List<T>, val errors: List<ImportError>)

internal fun parseBool(value: String?, default: Boolean): Boolean? = when (value?.trim()?.lowercase()) {
    null, "" -> default
    "1", "true", "y", "yes", "是", "v" -> true
    "0", "false", "n", "no", "否" -> false
    else -> null
}

object ProductCsv {
    val HEADER = listOf("sku", "barcode", "name", "price", "category", "unit", "stock", "low_stock", "taxable", "active")
    private val REQUIRED = listOf("sku", "name", "price")

    fun export(products: List<Product>, money: MoneyFormat): String = Csv.write(
        listOf(HEADER) + products.map { p ->
            listOf(
                p.sku, p.barcode.orEmpty(), p.name, money.formatPlain(p.price), p.category.orEmpty(), p.unit.orEmpty(),
                p.stockQty?.toString().orEmpty(), p.lowStockThreshold?.toString().orEmpty(),
                if (p.taxable) "1" else "0", if (p.active) "1" else "0",
            )
        },
    )

    /** Parses products; rows with errors are reported and skipped, valid rows are returned. */
    fun import(text: String, money: MoneyFormat): ImportResult<Product> {
        val table = try {
            CsvTable(Csv.parse(text))
        } catch (e: Csv.ParseException) {
            return ImportResult(emptyList(), listOf(ImportError(e.line, "CSV 格式錯誤")))
        }
        val missing = table.missingColumns(REQUIRED)
        if (missing.isNotEmpty()) return ImportResult(emptyList(), listOf(ImportError(1, "缺少欄位: ${missing.joinToString()}")))

        val products = mutableListOf<Product>()
        val errors = mutableListOf<ImportError>()
        val seenSku = mutableSetOf<String>()
        val seenBarcode = mutableSetOf<String>()
        for (r in table.records) {
            val problems = mutableListOf<String>()
            val sku = r["sku"]
            val name = r["name"]
            val price = r["price"]?.let { money.parse(it) }
            val barcode = r["barcode"]
            val stock = r["stock"]
            val lowStock = r["low_stock"]
            val taxable = parseBool(r["taxable"], true)
            val active = parseBool(r["active"], true)

            if (sku == null) problems += "缺少 sku"
            else if (!seenSku.add(sku.lowercase())) problems += "sku 重複: $sku"
            if (name == null) problems += "缺少品名"
            if (price == null) problems += "價格格式錯誤"
            else if (price.isNegative) problems += "價格不可為負數"
            if (barcode != null) {
                if (!BarcodeCheck.isPrintableCode128(barcode)) problems += "條碼含無效字元"
                else if (!seenBarcode.add(barcode)) problems += "條碼重複: $barcode"
            }
            if (stock != null && stock.toIntOrNull() == null) problems += "庫存必須是整數"
            if (lowStock != null && lowStock.toIntOrNull() == null) problems += "安全庫存必須是整數"
            if (taxable == null) problems += "taxable 值無效"
            if (active == null) problems += "active 值無效"

            if (problems.isNotEmpty()) {
                errors += ImportError(r.lineNumber, problems.joinToString("；"))
                continue
            }
            products += Product(
                sku = sku!!,
                barcode = barcode,
                name = name!!,
                price = price!!,
                category = r["category"],
                unit = r["unit"],
                stockQty = stock?.toInt(),
                lowStockThreshold = lowStock?.toInt(),
                taxable = taxable!!,
                active = active!!,
            )
        }
        return ImportResult(products, errors)
    }
}

object MemberCsv {
    val HEADER = listOf("member_no", "name", "phone", "card_uid", "discount_percent", "points", "active")

    fun export(members: List<Member>): String = Csv.write(
        listOf(HEADER) + members.map { m ->
            listOf(
                m.memberNo, m.name, m.phone.orEmpty(), m.cardUid.orEmpty(),
                (m.discountBp / 100.0).toString().removeSuffix(".0"), m.points.toString(), if (m.active) "1" else "0",
            )
        },
    )
}

object OrderCsv {
    val HEADER = listOf(
        "order_no", "created_at", "status", "cashier", "member_no", "sku", "name", "unit_price", "quantity",
        "line_discount", "line_net", "order_total", "tax", "payments", "refunded_at", "refund_reason",
    )

    /** One row per order line, with order-level columns repeated for spreadsheet pivoting. */
    fun export(orders: List<Order>, money: MoneyFormat, zone: ZoneId): String {
        val rows = mutableListOf(HEADER)
        for (o in orders) {
            val payments = o.payments.joinToString(" ") { "${it.method.name}:${money.formatPlain(it.amount)}" }
            for (line in o.lines) {
                rows += listOf(
                    o.orderNo, TimeFormats.dateTime(o.createdAt, zone), o.status.name, o.operatorName, o.memberNo.orEmpty(),
                    line.sku, line.name, money.formatPlain(line.unitPrice), line.quantity.toString(),
                    money.formatPlain(line.discount), money.formatPlain(line.net), money.formatPlain(o.total),
                    money.formatPlain(o.tax), payments, o.refundedAt?.let { TimeFormats.dateTime(it, zone) }.orEmpty(),
                    o.refundReason.orEmpty(),
                )
            }
        }
        return Csv.write(rows)
    }
}
