package me.longtai.core.common.codec

object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray, separator: String = ""): String {
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        bytes.forEachIndexed { index, b ->
            if (index > 0) sb.append(separator)
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Decodes hex, ignoring spaces, colons and dashes. Returns null for invalid input. */
    fun decode(text: String): ByteArray? {
        val clean = text.filterNot { it == ' ' || it == ':' || it == '-' }.uppercase()
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = DIGITS.indexOf(clean[i * 2])
            val lo = DIGITS.indexOf(clean[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Canonical form for NFC UIDs etc.: upper case, no separators. */
    fun normalize(text: String): String? = decode(text)?.let { encode(it) }
}

/** URL-safe Base64 without padding (RFC 4648 §5). java.util.Base64 needs API 26 on Android. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                .append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                    .append(ALPHABET[(n ushr 6) and 63])
            }
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray? {
        val s = text.trimEnd('=')
        if (s.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
