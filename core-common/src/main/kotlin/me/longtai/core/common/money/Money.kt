package me.longtai.core.common.money

/**
 * Monetary amount stored as an integer count of minor units (e.g. cents).
 * Never use floating point for money.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(Math.addExact(minor, other.minor))
    operator fun minus(other: Money) = Money(Math.subtractExact(minor, other.minor))
    operator fun times(factor: Int) = Money(Math.multiplyExact(minor, factor.toLong()))
    operator fun times(factor: Long) = Money(Math.multiplyExact(minor, factor))
    operator fun unaryMinus() = Money(Math.negateExact(minor))
    override fun compareTo(other: Money) = minor.compareTo(other.minor)

    val isZero get() = minor == 0L
    val isNegative get() = minor < 0L
    val isPositive get() = minor > 0L

    fun abs() = if (minor < 0) -this else this

    /** Applies a rate expressed in basis points (1% = 100bp), rounding half away from zero. */
    fun percentOf(basisPoints: Int): Money = Money(Rounding.divideHalfUp(Math.multiplyExact(minor, basisPoints.toLong()), 10_000L))

    fun coerceAtLeast(min: Money) = if (this < min) min else this
    fun coerceAtMost(max: Money) = if (this > max) max else this

    override fun toString() = "Money($minor)"

    companion object {
        val ZERO = Money(0)

        fun sum(items: Iterable<Money>): Money = items.fold(ZERO) { acc, m -> acc + m }
    }
}

fun Iterable<Money>.sum(): Money = Money.sum(this)

inline fun <T> Iterable<T>.sumOfMoney(selector: (T) -> Money): Money {
    var total = Money.ZERO
    for (item in this) total += selector(item)
    return total
}

object Rounding {
    /** Integer division rounding half away from zero. */
    fun divideHalfUp(numerator: Long, denominator: Long): Long {
        require(denominator != 0L) { "denominator must not be zero" }
        val negative = (numerator < 0) xor (denominator < 0)
        val n = absExact(numerator)
        val d = absExact(denominator)
        val q = n / d
        val r = n % d
        val rounded = if (r * 2 >= d) q + 1 else q
        return if (negative) -rounded else rounded
    }

    fun absExact(value: Long): Long {
        if (value == Long.MIN_VALUE) throw ArithmeticException("long overflow")
        return if (value < 0) -value else value
    }
}
