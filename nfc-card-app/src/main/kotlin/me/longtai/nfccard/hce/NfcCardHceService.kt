package me.longtai.nfccard.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import me.longtai.nfccard.data.CardRepository
import javax.inject.Inject

/**
 * Emulates an NFC Forum Type 4 Tag. A reader selects the NDEF application, then the Capability
 * Container and NDEF files, then reads them with READ BINARY. We answer from the currently
 * selected card's NDEF bytes ([CardRepository.activeFile]).
 *
 * Note: Android generates a random UID for HCE on every tap, so readers that identify cards by UID
 * will see a different UID each time. This tool is for exercising the NDEF-content read path.
 */
@AndroidEntryPoint
class NfcCardHceService : HostApduService() {

    @Inject lateinit var repository: CardRepository

    private var selected = Selected.NONE

    private enum class Selected { NONE, CC, NDEF }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return SW_ERROR
        if (apdu.size < 4) return SW_ERROR

        // SELECT by name (application) — only our AID is routed here.
        if (apdu.matchesHeader(0x00, 0xA4, 0x04, 0x00)) {
            selected = Selected.NONE
            return SW_OK
        }
        // SELECT by file id: 00 A4 00 0C 02 <fid hi> <fid lo>
        if (apdu.matchesHeader(0x00, 0xA4, 0x00, 0x0C) && apdu.size >= 7) {
            val fid = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
            selected = when (fid) {
                CC_FILE_ID -> Selected.CC
                NDEF_FILE_ID -> Selected.NDEF
                else -> return SW_FILE_NOT_FOUND
            }
            return SW_OK
        }
        // READ BINARY: 00 B0 <offset hi> <offset lo> <le>
        if (apdu.matchesHeader(0x00, 0xB0) && apdu.size >= 5) {
            val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            val le = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val data = when (selected) {
                Selected.CC -> CAPABILITY_CONTAINER
                Selected.NDEF -> repository.activeFile
                Selected.NONE -> return SW_FILE_NOT_FOUND
            }
            if (offset > data.size) return SW_END_OF_FILE
            val end = minOf(data.size, offset + le)
            return data.copyOfRange(offset, end) + SW_OK
        }
        return SW_ERROR
    }

    override fun onDeactivated(reason: Int) {
        selected = Selected.NONE
    }

    private fun ByteArray.matchesHeader(vararg header: Int): Boolean {
        if (size < header.size) return false
        return header.withIndex().all { (i, b) -> this[i] == b.toByte() }
    }

    private companion object {
        const val CC_FILE_ID = 0xE103
        const val NDEF_FILE_ID = 0xE104

        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_END_OF_FILE = byteArrayOf(0x6B, 0x00)
        val SW_ERROR = byteArrayOf(0x6F, 0x00)

        /**
         * Capability Container: mapping v2.0, one NDEF File Control TLV pointing at file E104,
         * max NDEF size 0x0400, read-only (write access 0xFF).
         */
        val CAPABILITY_CONTAINER = byteArrayOf(
            0x00, 0x0F, // CCLEN = 15
            0x20, // mapping version 2.0
            0x00, 0x3B, // MLe (max bytes read in one ReadBinary)
            0x00, 0x34, // MLc (max bytes in one command)
            0x04, 0x06, // NDEF File Control TLV: T=04, L=06
            0xE1.toByte(), 0x04, // NDEF file id
            0x04, 0x00, // max NDEF file size = 1024
            0x00, // read access granted
            0xFF.toByte(), // write access denied (read-only)
        )
    }
}
