package me.longtai.core.common.print

/** Thermal printers print at 203 dpi, i.e. 8 dots per millimetre. */
const val DOTS_PER_MM = 8

/**
 * Printable widths supported by the printer service's setPaperWidth().
 * [columns] is the number of half-width characters per line at [FontSize.NORMAL].
 */
enum class PaperWidth(val dots: Int, val mm: Int, val columns: Int) {
    MM58(384, 58, 32),
    MM80(576, 80, 48),
    ;

    companion object {
        fun fromDots(dots: Int): PaperWidth = entries.firstOrNull { it.dots == dots } ?: MM58
    }
}

/** Physical label stock dimensions, used by labelLocate(height, gap). */
data class LabelSize(val widthMm: Int, val heightMm: Int, val gapMm: Int) {
    init {
        require(widthMm in 10..80) { "label width must be 10..80 mm" }
        require(heightMm in 10..200) { "label height must be 10..200 mm" }
        require(gapMm in 0..20) { "label gap must be 0..20 mm" }
    }

    val widthDots get() = widthMm * DOTS_PER_MM
    val heightDots get() = heightMm * DOTS_PER_MM
    val gapDots get() = gapMm * DOTS_PER_MM

    companion object {
        val DEFAULT = LabelSize(widthMm = 50, heightMm = 30, gapMm = 2)
    }
}
