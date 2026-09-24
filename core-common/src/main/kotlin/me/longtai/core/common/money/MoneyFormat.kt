package me.longtai.core.common.money

/**
 * Formats and parses [Money] for a currency with a fixed number of fraction digits.
 * TWD is usually configured with 0 decimals, USD/EUR with 2.
 */
class MoneyFormat(
    val decimals: Int = 0,
    val symbol: String = "$",
) {
    init {
        require(decimals in 0..4) { "decimals must be 0..4" }
    }

    private val scale: Long = pow10(decimals)

    fun format(money: Money, withSymbol: Boolean = true): String {
        val negative = money.minor < 0
        val abs = Rounding.absExact(money.minor)
        val major = abs / scale
        val fraction = abs % scale
        val sb = StringBuilder()
        if (negative) sb.append('-')
        if (withSymbol) sb.append(symbol)
        sb.append(groupThousands(major))
        if (decimals > 0) {
            sb.append('.')
            sb.append(fraction.toString().padStart(decimals, '0'))
        }
        return sb.toString()
    }

    /** Plain amount without symbol or grouping, suitable for CSV export and edit fields. */
    fun formatPlain(money: Money): String {
        val negative = money.minor < 0
        val abs = Rounding.absExact(money.minor)
        val major = abs / scale
        val fraction = abs % scale
        val body = if (decimals > 0) "$major.${fraction.toString().padStart(decimals, '0')}" else "$major"
        return if (negative) "-$body" else body
    }

    /**
     * Parses user input such as "1,234.5", "$99", "-12". Returns null for invalid input
     * or when more fraction digits are given than the currency allows.
     */
    fun parse(input: String): Money? {
        var s = input.trim().replace(",", "")
        if (symbol.isNotEmpty()) s = s.replace(symbol, "")
        s = s.trim()
        if (s.isEmpty()) return null
        val negative = s.startsWith("-")
        if (negative) s = s.substring(1)
        if (s.isEmpty()) return null
        val parts = s.split('.')
        if (parts.size > 2) return null
        val majorPart = parts[0].ifEmpty { "0" }
        val fractionPart = if (parts.size == 2) parts[1] else ""
        if (!majorPart.all { it.isDigit() } || !fractionPart.all { it.isDigit() }) return null
        if (fractionPart.length > decimals) return null
        if (majorPart.length > 15) return null
        val major = majorPart.toLong()
        val fraction = if (decimals == 0) 0L else fractionPart.padEnd(decimals, '0').toLong()
        val minor = major * scale + fraction
        return Money(if (negative) -minor else minor)
    }

    fun ofMajor(major: Long): Money = Money(Math.multiplyExact(major, scale))

    private fun groupThousands(value: Long): String {
        val digits = value.toString()
        val sb = StringBuilder()
        digits.forEachIndexed { index, c ->
            if (index > 0 && (digits.length - index) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun pow10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }
}
