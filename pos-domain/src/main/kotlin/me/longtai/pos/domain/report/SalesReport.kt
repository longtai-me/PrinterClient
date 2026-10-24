package me.longtai.pos.domain.report

import me.longtai.core.common.money.Money
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.PaymentMethod
import me.longtai.pos.domain.model.Shift

data class ProductSales(val sku: String, val name: String, val quantity: Int, val amount: Money)

data class SalesSummary(
    val orderCount: Int,
    val refundCount: Int,
    val itemCount: Int,
    /** Total of all sales in the period, including ones refunded later. */
    val grossSales: Money,
    /** Total of refunds processed in the period. */
    val refunds: Money,
    val discounts: Money,
    val tax: Money,
    /** Net takings per payment method (sales minus refunds). */
    val byMethod: Map<PaymentMethod, Money>,
    val topProducts: List<ProductSales>,
) {
    val netSales: Money get() = grossSales - refunds
    val averageTicket: Money get() = if (orderCount == 0) Money.ZERO else Money(grossSales.minor / orderCount)
    fun methodTotal(method: PaymentMethod): Money = byMethod[method] ?: Money.ZERO
}

data class CashReconciliation(
    val openingCash: Money,
    val cashIn: Money,
    val countedCash: Money?,
) {
    val expectedCash: Money get() = openingCash + cashIn
    val variance: Money? get() = countedCash?.let { it - expectedCash }
}

object SalesReport {

    /**
     * @param sales orders created in the period (any status)
     * @param refunds orders refunded in the period (may have been sold earlier)
     */
    fun summarize(sales: List<Order>, refunds: List<Order>, topN: Int = 5): SalesSummary {
        val byMethod = linkedMapOf<PaymentMethod, Money>()
        PaymentMethod.entries.forEach { byMethod[it] = Money.ZERO }
        sales.forEach { order -> order.payments.forEach { byMethod[it.method] = byMethod.getValue(it.method) + it.amount } }
        refunds.forEach { order -> order.payments.forEach { byMethod[it.method] = byMethod.getValue(it.method) - it.amount } }

        val refundedIds = refunds.map { it.orderNo }.toSet()
        val kept = sales.filterNot { it.orderNo in refundedIds }
        val products = kept.flatMap { it.lines }
            .groupBy { it.sku }
            .map { (sku, lines) -> ProductSales(sku, lines.last().name, lines.sumOf { it.quantity }, Money.sum(lines.map { it.net })) }
            .sortedWith(compareByDescending<ProductSales> { it.quantity }.thenByDescending { it.amount.minor })
            .take(topN)

        return SalesSummary(
            orderCount = sales.size,
            refundCount = refunds.size,
            itemCount = kept.sumOf { it.itemCount },
            grossSales = Money.sum(sales.map { it.total }),
            refunds = Money.sum(refunds.map { it.total }),
            discounts = Money.sum(kept.map { it.totalDiscount }),
            tax = Money.sum(kept.map { it.tax }),
            byMethod = byMethod.filterValues { !it.isZero },
            topProducts = products,
        )
    }

    fun reconcile(shift: Shift, summary: SalesSummary, countedCash: Money? = shift.countedCash) =
        CashReconciliation(shift.openingCash, summary.methodTotal(PaymentMethod.CASH), countedCash)
}
