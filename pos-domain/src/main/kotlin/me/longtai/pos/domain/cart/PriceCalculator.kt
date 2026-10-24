package me.longtai.pos.domain.cart

import me.longtai.core.common.money.Money
import me.longtai.core.common.money.Rounding
import me.longtai.pos.domain.model.TaxConfig
import me.longtai.pos.domain.model.TaxMode

data class LineTotals(
    val lineId: Long,
    val gross: Money,
    val discount: Money,
    val net: Money,
)

data class CartTotals(
    val lines: List<LineTotals>,
    val itemCount: Int,
    /** Sum of unit price × quantity. */
    val gross: Money,
    val lineDiscounts: Money,
    /** Gross minus line discounts. */
    val subtotal: Money,
    val memberDiscount: Money,
    val orderDiscount: Money,
    val tax: Money,
    /** Amount due. */
    val total: Money,
) {
    val totalDiscount: Money get() = lineDiscounts + memberDiscount + orderDiscount

    fun line(lineId: Long): LineTotals? = lines.firstOrNull { it.lineId == lineId }

    companion object {
        val EMPTY = CartTotals(emptyList(), 0, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO)
    }
}

/**
 * Pricing order: line discounts → member discount (percentage of subtotal) → order discount
 * → tax. Tax is computed on the taxable share of the discounted total so that discounts
 * are spread proportionally across taxable and tax-free items.
 */
object PriceCalculator {

    fun calculate(cart: Cart, tax: TaxConfig): CartTotals {
        if (cart.isEmpty) return CartTotals.EMPTY

        val lineTotals = cart.lines.map { line ->
            val gross = line.unitPrice * line.quantity
            val discount = line.discount?.amountFor(gross) ?: Money.ZERO
            LineTotals(line.lineId, gross, discount, gross - discount)
        }
        val gross = Money.sum(lineTotals.map { it.gross })
        val lineDiscounts = Money.sum(lineTotals.map { it.discount })
        val subtotal = gross - lineDiscounts

        val memberBp = cart.member?.discountBp ?: 0
        val memberDiscount = if (memberBp > 0) subtotal.percentOf(memberBp).coerceAtMost(subtotal) else Money.ZERO
        val afterMember = subtotal - memberDiscount

        val orderDiscount = cart.orderDiscount?.amountFor(afterMember) ?: Money.ZERO
        val discounted = afterMember - orderDiscount

        val taxableNet = Money.sum(
            cart.lines.zip(lineTotals).filter { (line, _) -> line.product.taxable }.map { (_, totals) -> totals.net },
        )
        val taxableBase = if (subtotal.isZero) {
            Money.ZERO
        } else {
            Money(Rounding.divideHalfUp(Math.multiplyExact(discounted.minor, taxableNet.minor), subtotal.minor))
        }

        val taxAmount = when (tax.mode) {
            TaxMode.NONE -> Money.ZERO
            TaxMode.INCLUSIVE -> Money(Rounding.divideHalfUp(Math.multiplyExact(taxableBase.minor, tax.rateBp.toLong()), 10_000L + tax.rateBp))
            TaxMode.EXCLUSIVE -> taxableBase.percentOf(tax.rateBp)
        }
        val total = if (tax.mode == TaxMode.EXCLUSIVE) discounted + taxAmount else discounted

        return CartTotals(
            lines = lineTotals,
            itemCount = cart.itemCount,
            gross = gross,
            lineDiscounts = lineDiscounts,
            subtotal = subtotal,
            memberDiscount = memberDiscount,
            orderDiscount = orderDiscount,
            tax = taxAmount,
            total = total,
        )
    }
}
