package me.longtai.pos.domain.model

import me.longtai.core.common.money.Money

data class Product(
    val id: Long = 0,
    val sku: String,
    val barcode: String? = null,
    val name: String,
    val price: Money,
    val category: String? = null,
    val unit: String? = null,
    /** null = stock is not tracked for this product. */
    val stockQty: Int? = null,
    val lowStockThreshold: Int? = null,
    val taxable: Boolean = true,
    val active: Boolean = true,
) {
    val isLowStock: Boolean
        get() = stockQty != null && lowStockThreshold != null && stockQty <= lowStockThreshold
}

data class Member(
    val id: Long = 0,
    val memberNo: String,
    val name: String,
    val phone: String? = null,
    /** Normalised (upper-case hex) NFC card UID. */
    val cardUid: String? = null,
    /** Member discount in basis points, 500 = 5%. */
    val discountBp: Int = 0,
    val points: Long = 0,
    val active: Boolean = true,
)

sealed interface Discount {
    /** Discount amount for [base], never negative and never more than [base]. */
    fun amountFor(base: Money): Money

    data class Percent(val basisPoints: Int) : Discount {
        init {
            require(basisPoints in 0..10_000) { "percent discount must be 0..100%" }
        }

        override fun amountFor(base: Money) = base.percentOf(basisPoints).coerceAtLeast(Money.ZERO).coerceAtMost(base)
    }

    data class Amount(val amount: Money) : Discount {
        init {
            require(!amount.isNegative) { "discount amount must not be negative" }
        }

        override fun amountFor(base: Money) = amount.coerceAtMost(base).coerceAtLeast(Money.ZERO)
    }
}

enum class TaxMode(val label: String) {
    /** Prices include tax (Taiwan business tax). Tax is shown but not added. */
    INCLUSIVE("內含"),

    /** Tax is added on top of the prices. */
    EXCLUSIVE("外加"),

    /** No tax shown. */
    NONE("免稅"),
}

data class TaxConfig(val mode: TaxMode = TaxMode.INCLUSIVE, val rateBp: Int = 500) {
    init {
        require(rateBp in 0..10_000) { "tax rate must be 0..100%" }
    }
}

enum class PaymentMethod(val label: String) {
    CASH("現金"),
    CARD("信用卡"),
    MOBILE("行動支付"),
    VOUCHER("禮券"),
    OTHER("其他"),
}

data class Payment(
    val method: PaymentMethod,
    /** Amount applied to the order. */
    val amount: Money,
    /** Amount handed over by the customer (cash may exceed [amount]). */
    val tendered: Money = amount,
    /** Card approval code / mobile payment transaction id. */
    val reference: String? = null,
) {
    val change: Money get() = tendered - amount
}

enum class OrderStatus(val label: String) {
    COMPLETED("已完成"),
    REFUNDED("已退貨"),
}

data class OrderLine(
    val productId: Long?,
    val sku: String,
    val name: String,
    val unitPrice: Money,
    val quantity: Int,
    val discount: Money,
    val net: Money,
    val taxable: Boolean,
)

data class Order(
    val id: Long = 0,
    val orderNo: String,
    val createdAt: Long,
    val operatorId: Long,
    val operatorName: String,
    val shiftId: Long,
    val memberId: Long? = null,
    val memberNo: String? = null,
    val memberName: String? = null,
    val lines: List<OrderLine>,
    val gross: Money,
    val lineDiscounts: Money,
    val memberDiscount: Money,
    val orderDiscount: Money,
    val taxMode: TaxMode,
    val taxRateBp: Int,
    val tax: Money,
    val total: Money,
    val payments: List<Payment>,
    val pointsEarned: Long = 0,
    val status: OrderStatus = OrderStatus.COMPLETED,
    val refundedAt: Long? = null,
    val refundShiftId: Long? = null,
    val refundOperatorName: String? = null,
    val refundReason: String? = null,
) {
    val itemCount: Int get() = lines.sumOf { it.quantity }
    val totalDiscount: Money get() = lineDiscounts + memberDiscount + orderDiscount
    val change: Money get() = payments.fold(Money.ZERO) { acc, p -> acc + p.change }
}

data class Shift(
    val id: Long = 0,
    val operatorId: Long,
    val operatorName: String,
    val openedAt: Long,
    val openingCash: Money,
    val closedAt: Long? = null,
    val closedByName: String? = null,
    val countedCash: Money? = null,
    val note: String? = null,
) {
    val isOpen: Boolean get() = closedAt == null
}

data class StoreProfile(
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val taxId: String? = null,
    val footer: String? = null,
)

/** Earn one point for every [amountPerPoint] spent; disabled when not positive. */
data class LoyaltyRule(val amountPerPoint: Money) {
    fun pointsFor(total: Money): Long =
        if (!amountPerPoint.isPositive || !total.isPositive) 0 else total.minor / amountPerPoint.minor
}
