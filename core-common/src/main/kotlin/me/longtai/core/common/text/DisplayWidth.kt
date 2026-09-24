package me.longtai.core.common.text

/**
 * Monospace display width helpers. CJK and full-width characters occupy two columns
 * on receipt printers and in monospace previews; ASCII occupies one.
 */
object DisplayWidth {

    fun of(codePoint: Int): Int = when {
        codePoint == 0 -> 0
        Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() -> 0
        Character.getType(codePoint) == Character.ENCLOSING_MARK.toInt() -> 0
        codePoint == 0x200B -> 0
        isWide(codePoint) -> 2
        else -> 1
    }

    fun of(text: CharSequence): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            width += of(cp)
            i += Character.charCount(cp)
        }
        return width
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F ||
            cp in 0x2E80..0x303E ||
            cp in 0x3041..0x33FF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF ||
            cp in 0xA000..0xA4CF ||
            cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F ||
            cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F300..0x1F64F ||
            cp in 0x1F900..0x1F9FF ||
            cp in 0x20000..0x2FFFD ||
            cp in 0x30000..0x3FFFD

    /** Truncates to at most [width] columns, appending [ellipsis] when shortened. */
    fun truncate(text: String, width: Int, ellipsis: String = "…"): String {
        if (width <= 0) return ""
        if (of(text) <= width) return text
        val ellipsisWidth = of(ellipsis)
        val budget = (width - ellipsisWidth).coerceAtLeast(0)
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val w = of(cp)
            if (used + w > budget) break
            sb.appendCodePoint(cp)
            used += w
            i += Character.charCount(cp)
        }
        if (ellipsisWidth <= width) sb.append(ellipsis)
        return sb.toString()
    }

    fun padEnd(text: String, width: Int, pad: Char = ' '): String {
        val w = of(text)
        return if (w >= width) text else text + pad.toString().repeat(width - w)
    }

    fun padStart(text: String, width: Int, pad: Char = ' '): String {
        val w = of(text)
        return if (w >= width) text else pad.toString().repeat(width - w) + text
    }

    fun center(text: String, width: Int): String {
        val w = of(text)
        if (w >= width) return text
        val left = (width - w) / 2
        return " ".repeat(left) + text + " ".repeat(width - w - left)
    }

    /** Hard-wraps text into lines no wider than [width] columns, honouring explicit newlines. */
    fun wrap(text: String, width: Int): List<String> {
        require(width > 0) { "width must be positive" }
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                lines += ""
                continue
            }
            val sb = StringBuilder()
            var used = 0
            var i = 0
            while (i < paragraph.length) {
                val cp = Character.codePointAt(paragraph, i)
                val w = of(cp)
                if (used + w > width && sb.isNotEmpty()) {
                    lines += sb.toString()
                    sb.setLength(0)
                    used = 0
                }
                sb.appendCodePoint(cp)
                used += w
                i += Character.charCount(cp)
            }
            lines += sb.toString()
        }
        return lines
    }
}
