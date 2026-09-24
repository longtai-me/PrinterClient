package me.longtai.pos.domain.cart

import me.longtai.core.common.money.Money
import me.longtai.pos.domain.model.Discount
import me.longtai.pos.domain.model.Member
import me.longtai.pos.domain.model.Product

data class CartLine(
    val lineId: Long,
    val product: Product,
    val quantity: Int,
    val unitPrice: Money = product.price,
    val discount: Discount? = null,
)

/** Immutable shopping cart; every operation returns a new cart. */
data class Cart(
    val lines: List<CartLine> = emptyList(),
    val member: Member? = null,
    val orderDiscount: Discount? = null,
    private val nextLineId: Long = 1,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val itemCount: Int get() = lines.sumOf { it.quantity }

    /**
     * Adds [quantity] of [product]. Scanning the same product again increments the
     * existing line unless that line has its own price or discount.
     */
    fun add(product: Product, quantity: Int = 1): Cart {
        require(quantity > 0) { "quantity must be positive" }
        val existing = lines.lastOrNull {
            it.product.id == product.id && it.product.sku == product.sku &&
                it.discount == null && it.unitPrice == product.price
        }
        return if (existing != null) {
            setQuantity(existing.lineId, existing.quantity + quantity)
        } else {
            copy(lines = lines + CartLine(nextLineId, product, quantity), nextLineId = nextLineId + 1)
        }
    }

    /** Sets a line's quantity; zero or less removes the line. */
    fun setQuantity(lineId: Long, quantity: Int): Cart =
        if (quantity <= 0) remove(lineId)
        else copy(lines = lines.map { if (it.lineId == lineId) it.copy(quantity = minOf(quantity, MAX_QUANTITY)) else it })

    fun increment(lineId: Long): Cart = line(lineId)?.let { setQuantity(lineId, it.quantity + 1) } ?: this
    fun decrement(lineId: Long): Cart = line(lineId)?.let { setQuantity(lineId, it.quantity - 1) } ?: this

    fun remove(lineId: Long): Cart = copy(lines = lines.filterNot { it.lineId == lineId })

    fun setLineDiscount(lineId: Long, discount: Discount?): Cart =
        copy(lines = lines.map { if (it.lineId == lineId) it.copy(discount = discount) else it })

    fun setUnitPrice(lineId: Long, price: Money): Cart {
        require(!price.isNegative) { "price must not be negative" }
        return copy(lines = lines.map { if (it.lineId == lineId) it.copy(unitPrice = price) else it })
    }

    fun withMember(member: Member?): Cart = copy(member = member)
    fun withOrderDiscount(discount: Discount?): Cart = copy(orderDiscount = discount)
    fun clear(): Cart = Cart(nextLineId = nextLineId)

    fun line(lineId: Long): CartLine? = lines.firstOrNull { it.lineId == lineId }

    companion object {
        const val MAX_QUANTITY = 9_999
    }
}
