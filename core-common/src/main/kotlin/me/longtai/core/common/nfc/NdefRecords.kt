package me.longtai.core.common.nfc

/** Decoders for NDEF well-known record payloads (NFC Forum RTD Text / URI). */
object NdefRecords {

    /** RTD "T" payload: status byte (bit7 = UTF-16, bits0-5 = language length), language, text. */
    fun decodeText(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val langLength = status and 0x3F
        val start = 1 + langLength
        if (start > payload.size) return null
        val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
        return String(payload, start, payload.size - start, charset)
    }

    fun encodeText(text: String, language: String = "en"): ByteArray {
        val lang = language.toByteArray(Charsets.US_ASCII)
        require(lang.size <= 0x3F) { "language code too long" }
        val body = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(lang.size.toByte()) + lang + body
    }

    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://",
        "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    /** RTD "U" payload: prefix code byte followed by the UTF-8 remainder. */
    fun decodeUri(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val code = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrElse(code) { "" }
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }
}
