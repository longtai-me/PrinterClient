package me.longtai.core.common.print

import me.longtai.core.common.text.DisplayWidth

/**
 * Device independent description of a printout (receipt, report, label).
 * The hardware layer renders it through the printer service; [PlainTextRenderer]
 * renders it as monospace text for on-screen previews and tests.
 */
data class PrintDocument(val elements: List<PrintElement>) {
    operator fun plus(other: PrintDocument) = PrintDocument(elements + other.elements)
}

enum class Align { LEFT, CENTER, RIGHT }

/** Text sizes in printer pixels; the service renders text with Android fonts at this size. */
enum class FontSize(val px: Int) {
    SMALL(20),
    NORMAL(24),
    LARGE(32),
    XLARGE(48),
}

/** Values match the `symbology` parameter of IPrinterService.printBarcode. */
enum class BarcodeSymbology(val code: Int) {
    CODE128(0),
    CODE39(1),
    CODE93(2),
    UPC_A(3),
    UPC_E(4),
    EAN13(5),
    EAN8(6),
    ITF(7),
    CODABAR(8),
}

/** Values match the `textPosition` parameter of IPrinterService.printBarcode. */
enum class HriPosition(val code: Int) {
    NONE(0),
    ABOVE(1),
    BELOW(2),
    BOTH(3),
}

enum class DividerStyle { DASHED, SOLID, DOUBLE }

sealed interface PrintElement {

    data class Text(
        val text: String,
        val align: Align = Align.LEFT,
        val size: FontSize = FontSize.NORMAL,
        val bold: Boolean = false,
        val underline: Boolean = false,
    ) : PrintElement

    data class Row(
        val cells: List<Cell>,
        val size: FontSize = FontSize.NORMAL,
        val bold: Boolean = false,
    ) : PrintElement {
        init {
            require(cells.isNotEmpty()) { "row needs at least one cell" }
        }
    }

    data class Divider(val style: DividerStyle = DividerStyle.DASHED) : PrintElement

    data class Barcode(
        val content: String,
        val symbology: BarcodeSymbology = BarcodeSymbology.CODE128,
        val heightPx: Int = 80,
        /** 0 lets the renderer choose a width that fits the paper. */
        val widthPx: Int = 0,
        val hri: HriPosition = HriPosition.BELOW,
        val align: Align = Align.CENTER,
    ) : PrintElement

    data class QrCode(
        val content: String,
        val sizePx: Int = 240,
        val align: Align = Align.CENTER,
    ) : PrintElement

    data class Feed(val px: Int) : PrintElement
}

data class Cell(val text: String, val weight: Int = 1, val align: Align = Align.LEFT) {
    init {
        require(weight > 0) { "weight must be positive" }
    }
}

class PrintDocumentBuilder {
    private val elements = mutableListOf<PrintElement>()

    fun text(
        text: String,
        align: Align = Align.LEFT,
        size: FontSize = FontSize.NORMAL,
        bold: Boolean = false,
        underline: Boolean = false,
    ) {
        elements += PrintElement.Text(text, align, size, bold, underline)
    }

    fun title(text: String, size: FontSize = FontSize.LARGE) = text(text, Align.CENTER, size, bold = true)

    fun center(text: String, size: FontSize = FontSize.NORMAL, bold: Boolean = false) =
        text(text, Align.CENTER, size, bold)

    fun row(vararg cells: Cell, size: FontSize = FontSize.NORMAL, bold: Boolean = false) {
        elements += PrintElement.Row(cells.toList(), size, bold)
    }

    /** Label on the left, value on the right; column weights follow the content widths. */
    fun pair(left: String, right: String, size: FontSize = FontSize.NORMAL, bold: Boolean = false) =
        row(
            Cell(left, DisplayWidth.of(left) + 1, Align.LEFT),
            Cell(right, DisplayWidth.of(right) + 1, Align.RIGHT),
            size = size,
            bold = bold,
        )

    fun divider(style: DividerStyle = DividerStyle.DASHED) {
        elements += PrintElement.Divider(style)
    }

    fun barcode(
        content: String,
        symbology: BarcodeSymbology = BarcodeSymbology.CODE128,
        heightPx: Int = 80,
        widthPx: Int = 0,
        hri: HriPosition = HriPosition.BELOW,
        align: Align = Align.CENTER,
    ) {
        elements += PrintElement.Barcode(content, symbology, heightPx, widthPx, hri, align)
    }

    fun qrCode(content: String, sizePx: Int = 240, align: Align = Align.CENTER) {
        elements += PrintElement.QrCode(content, sizePx, align)
    }

    fun feed(px: Int = 24) {
        elements += PrintElement.Feed(px)
    }

    fun append(document: PrintDocument) {
        elements += document.elements
    }

    fun build() = PrintDocument(elements.toList())
}

fun printDocument(block: PrintDocumentBuilder.() -> Unit): PrintDocument =
    PrintDocumentBuilder().apply(block).build()
