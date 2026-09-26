package me.longtai.nfccard.hce

import me.longtai.core.common.nfc.NdefRecords
import java.io.ByteArrayOutputStream

/**
 * Builds the bytes an NFC Forum Type 4 Tag serves: an NDEF message wrapped in the NDEF file
 * (a 2-byte length prefix). Readers, including this project's [me.longtai.core.hardware.nfc.NfcReader],
 * read the message and decode the Text record.
 */
object NdefEmu {

    /** A single well-known Text record as an NDEF message. */
    fun textMessage(text: String, language: String = "en"): ByteArray {
        val payload = NdefRecords.encodeText(text, language)
        val out = ByteArrayOutputStream()
        val type = 'T'.code
        if (payload.size < 256) {
            out.write(0xD1) // MB=1 ME=1 SR=1 TNF=001 (well known)
            out.write(1) // type length
            out.write(payload.size) // short payload length
            out.write(type)
            out.write(payload)
        } else {
            out.write(0xC1) // MB=1 ME=1 SR=0 TNF=001
            out.write(1)
            val n = payload.size
            out.write((n ushr 24) and 0xFF)
            out.write((n ushr 16) and 0xFF)
            out.write((n ushr 8) and 0xFF)
            out.write(n and 0xFF)
            out.write(type)
            out.write(payload)
        }
        return out.toByteArray()
    }

    /** Wraps an NDEF message into the Type 4 NDEF file: 2-byte NLEN followed by the message. */
    fun file(message: ByteArray): ByteArray {
        val len = message.size
        return byteArrayOf(((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + message
    }

    fun textFile(text: String, language: String = "en"): ByteArray = file(textMessage(text, language))

    /** Empty NDEF file (NLEN = 0), served when no card is selected. */
    val EMPTY_FILE: ByteArray = byteArrayOf(0x00, 0x00)
}
