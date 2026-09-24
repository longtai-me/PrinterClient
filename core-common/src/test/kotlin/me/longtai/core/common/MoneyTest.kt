package me.longtai.core.common

import me.longtai.core.common.money.Money
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.common.money.Rounding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {

    @Test
    fun `divideHalfUp rounds half away from zero`() {
        assertEquals(3, Rounding.divideHalfUp(5, 2))
        assertEquals(2, Rounding.divideHalfUp(9, 4))
        assertEquals(-3, Rounding.divideHalfUp(-5, 2))
        assertEquals(0, Rounding.divideHalfUp(1, 3))
    }

    @Test
    fun `percentOf uses basis points`() {
        assertEquals(Money(10), Money(100).percentOf(1000))
        assertEquals(Money(5), Money(99).percentOf(500)) // 4.95 -> 5
        assertEquals(Money(0), Money(9).percentOf(500)) // 0.45 -> 0
    }

    @Test
    fun `format with zero decimals groups thousands`() {
        val f = MoneyFormat(decimals = 0, symbol = "$")
        assertEquals("$1,234,567", f.format(Money(1_234_567)))
        assertEquals("-$50", f.format(Money(-50)))
        assertEquals("0", f.formatPlain(Money.ZERO))
    }

    @Test
    fun `format with two decimals`() {
        val f = MoneyFormat(decimals = 2, symbol = "NT$")
        assertEquals("NT$12.05", f.format(Money(1205)))
        assertEquals("12.05", f.formatPlain(Money(1205)))
        assertEquals("-0.50", f.formatPlain(Money(-50)))
    }

    @Test
    fun `parse accepts symbols and grouping and rejects invalid`() {
        val f = MoneyFormat(decimals = 2, symbol = "$")
        assertEquals(Money(123450), f.parse("$1,234.5"))
        assertEquals(Money(-1200), f.parse("-12"))
        assertEquals(Money(5), f.parse(".05"))
        assertNull(f.parse("1.234"))
        assertNull(f.parse("abc"))
        assertNull(f.parse(""))
        assertNull(f.parse("1.2.3"))
        val twd = MoneyFormat(decimals = 0)
        assertEquals(Money(100), twd.parse("100"))
        assertNull(twd.parse("100.5"))
    }
}
