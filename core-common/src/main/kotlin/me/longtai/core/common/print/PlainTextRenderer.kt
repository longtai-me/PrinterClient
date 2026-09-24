package me.longtai.core.common.print

import me.longtai.core.common.text.DisplayWidth

/**
 * Renders a [PrintDocument] as monospace text lines. Used for on-screen receipt
 * previews, the simulated printer and unit tests.
 */
object PlainTextRenderer {

    fun render(document: PrintDocument, columns: Int): List<String> {
        require(columns >= 8) { "columns must be at least 8" }
        val out = mutableListOf<String>()
        for (element in document.elements) {
            when (element) {
                is PrintElement.Text -> renderText(element, columns, out)
                is PrintElement.Row -> renderRow(element, columns, out)
                is PrintElement.Divider -> out += dividerChar(element.style).toString().repeat(columns)
                is PrintElement.Barcode -> {
                    val body = "‖▌${element.content}▌‖"
                    if (element.hri == HriPosition.ABOVE || element.hri == HriPosition.BOTH) {
                        out += align(element.content, columns, element.align)
                    }
                    out += align(DisplayWidth.truncate("[${element.symbology.name}] $body", columns), columns, element.align)
                    if (element.hri == HriPosition.BELOW || element.hri == HriPosition.BOTH) {
                        out += align(element.content, columns, element.align)
                    }
                }
                is PrintElement.QrCode -> {
                    out += align("▣ QR ▣", columns, element.align)
                    out += align(DisplayWidth.truncate(element.content, columns), columns, element.align)
                }
                is PrintElement.Feed -> repeat(maxOf(1, element.px / FontSize.NORMAL.px)) { out += "" }
            }
        }
        return out.map { it.trimEnd() }
    }

    fun renderToString(document: PrintDocument, columns: Int): String = render(document, columns).joinToString("\n")

    /** Columns available for a given font size (larger fonts fit fewer characters). */
    fun columnsFor(size: FontSize, columns: Int): Int = maxOf(4, columns * FontSize.NORMAL.px / size.px)

    private fun renderText(element: PrintElement.Text, columns: Int, out: MutableList<String>) {
        val width = columnsFor(element.size, columns)
        for (line in DisplayWidth.wrap(element.text, width)) {
            out += align(line, width, element.align)
        }
    }

    private fun renderRow(row: PrintElement.Row, columns: Int, out: MutableList<String>) {
        val total = columnsFor(row.size, columns)
        val widths = distribute(total, row.cells.map { it.weight })
        val wrapped = row.cells.mapIndexed { index, cell ->
            val gap = if (index < row.cells.lastIndex) 1 else 0
            val inner = maxOf(1, widths[index] - gap)
            DisplayWidth.wrap(cell.text, inner).map { line ->
                DisplayWidth.padEnd(align(line, inner, cell.align), inner) + " ".repeat(gap)
            }
        }
        val height = wrapped.maxOf { it.size }
        for (lineIndex in 0 until height) {
            val sb = StringBuilder()
            wrapped.forEachIndexed { cellIndex, lines ->
                sb.append(lines.getOrNull(lineIndex) ?: " ".repeat(widths[cellIndex]))
            }
            out += sb.toString()
        }
    }

    internal fun distribute(total: Int, weights: List<Int>): List<Int> {
        val sum = weights.sum()
        val widths = weights.map { total * it / sum }.toMutableList()
        widths[widths.lastIndex] += total - widths.sum()
        return widths
    }

    private fun align(text: String, width: Int, align: Align): String = when (align) {
        Align.LEFT -> text
        Align.CENTER -> DisplayWidth.center(text, width)
        Align.RIGHT -> DisplayWidth.padStart(text, width)
    }

    private fun dividerChar(style: DividerStyle) = when (style) {
        DividerStyle.DASHED -> '-'
        DividerStyle.SOLID -> '_'
        DividerStyle.DOUBLE -> '='
    }
}
