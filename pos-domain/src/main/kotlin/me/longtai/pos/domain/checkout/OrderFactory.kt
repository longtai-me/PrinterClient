package me.longtai.pos.domain.checkout

import me.longtai.core.common.time.TimeFormats
import me.longtai.pos.domain.cart.Cart
import me.longtai.pos.domain.cart.CartTotals
import me.longtai.pos.domain.model.LoyaltyRule
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.OrderLine
import me.longtai.pos.domain.model.TaxConfig
import java.time.LocalDate

object OrderNumbers {
    /** e.g. 20260924-01-0007: business date, device code, daily sequence. */
    fun format(date: LocalDate, deviceCode: String, sequence: Int): String {
        require(sequence in 1..99_999) { "sequence out of range" }
        return "${TimeFormats.compactDate(date)}-$deviceCode-${sequence.toString().padStart(4, '0')}"
    }

    fun isValidDeviceCode(code: String) = code.length in 1..4 && code.all { it.isLetterOrDigit() && it.code < 128 }
}

data class Cashier(val id: Long, val name: String)

class CheckoutException(message: String) : IllegalStateException(message)

object OrderFactory {

    fun create(
        cart: Cart,
        totals: CartTotals,
        tender: Tender,
        tax: TaxConfig,
        orderNo: String,
        createdAt: Long,
        cashier: Cashier,
        shiftId: Long,
        loyalty: LoyaltyRule,
    ): Order {
        if (cart.isEmpty) throw CheckoutException("購物車是空的")
        if (tender.total != totals.total) throw CheckoutException("付款金額與訂單金額不一致")
        if (!tender.isComplete) throw CheckoutException("尚未付清")

        val lines = cart.lines.map { line ->
            val lt = totals.line(line.lineId) ?: throw CheckoutException("金額計算不一致")
            OrderLine(
                productId = line.product.id.takeIf { it > 0 },
                sku = line.product.sku,
                name = line.product.name,
                unitPrice = line.unitPrice,
                quantity = line.quantity,
                discount = lt.discount,
                net = lt.net,
                taxable = line.product.taxable,
            )
        }
        val member = cart.member
        return Order(
            orderNo = orderNo,
            createdAt = createdAt,
            operatorId = cashier.id,
            operatorName = cashier.name,
            shiftId = shiftId,
            memberId = member?.id?.takeIf { it > 0 },
            memberNo = member?.memberNo,
            memberName = member?.name,
            lines = lines,
            gross = totals.gross,
            lineDiscounts = totals.lineDiscounts,
            memberDiscount = totals.memberDiscount,
            orderDiscount = totals.orderDiscount,
            taxMode = tax.mode,
            taxRateBp = tax.rateBp,
            tax = totals.tax,
            total = totals.total,
            payments = tender.payments,
            pointsEarned = if (member != null) loyalty.pointsFor(totals.total) else 0,
        )
    }

    /** Stock movements for a sale (negative) or refund (positive), keyed by product id. */
    fun stockDelta(order: Order, refund: Boolean): Map<Long, Int> =
        order.lines.filter { it.productId != null }
            .groupBy { it.productId!! }
            .mapValues { (_, lines) -> lines.sumOf { it.quantity } * if (refund) 1 else -1 }
}
