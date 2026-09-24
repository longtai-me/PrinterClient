package me.longtai.core.common

import me.longtai.core.common.print.Align
import me.longtai.core.common.print.Cell
import me.longtai.core.common.print.FontSize
import me.longtai.core.common.print.PlainTextRenderer
import me.longtai.core.common.print.printDocument
import me.longtai.core.common.text.DisplayWidth
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextAndPrintTest {

    @Test
    fun `cjk characters are double width`() {
        assertEquals(4, DisplayWidth.of("收銀"))
        assertEquals(6, DisplayWidth.of("ab收銀"))
        assertEquals(2, DisplayWidth.of("ＡＢ") / 2)
    }

    @Test
    fun `truncate respects display width`() {
        assertEquals("收銀…", DisplayWidth.truncate("收銀系統測試", 5))
        assertEquals("abc", DisplayWidth.truncate("abc", 5))
        assertTrue(DisplayWidth.of(DisplayWidth.truncate("蘋果蘋果蘋果", 7)) <= 7)
    }

    @Test
    fun `wrap splits long cjk text`() {
        val lines = DisplayWidth.wrap("一二三四五六七八九十", 8)
        assertEquals(listOf("一二三四", "五六七八", "九十"), lines)
        assertEquals(listOf("a", "", "b"), DisplayWidth.wrap("a\n\nb", 8))
    }

    @Test
    fun `renderer lays out rows and pairs to the column width`() {
        val doc = printDocument {
            title("好好商店", FontSize.NORMAL)
            divider()
            pair("合計", "$100")
            row(Cell("蘋果", 2), Cell("x2", 1, Align.RIGHT), Cell("40", 1, Align.RIGHT))
        }
        val lines = PlainTextRenderer.render(doc, 32)
        assertEquals("            好好商店", lines[0])
        assertEquals("-".repeat(32), lines[1])
        assertEquals(32, DisplayWidth.of(lines[2]))
        assertTrue(lines[2].startsWith("合計"))
        assertTrue(lines[2].endsWith("$100"))
        assertTrue(lines[3].startsWith("蘋果"))
        assertTrue(lines[3].endsWith("40"))
        lines.forEach { assertTrue(DisplayWidth.of(it) <= 32, "line too wide: '$it'") }
    }

    @Test
    fun `large text uses fewer columns`() {
        assertEquals(16, PlainTextRenderer.columnsFor(FontSize.XLARGE, 32))
        val doc = printDocument { text("12345678901234567890", size = FontSize.XLARGE) }
        val lines = PlainTextRenderer.render(doc, 32)
        assertEquals(listOf("1234567890123456", "7890"), lines)
    }

    @Test
    fun `distribute gives remainder to last column`() {
        assertEquals(listOf(16, 8, 8), PlainTextRenderer.distribute(32, listOf(2, 1, 1)))
        assertEquals(listOf(10, 10, 12), PlainTextRenderer.distribute(32, listOf(1, 1, 1)))
    }
}
