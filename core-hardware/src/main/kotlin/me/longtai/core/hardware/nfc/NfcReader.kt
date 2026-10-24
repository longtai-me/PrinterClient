package me.longtai.core.hardware.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.longtai.core.common.codec.Hex
import me.longtai.core.common.nfc.NdefRecords
import me.longtai.core.common.scan.ScanDebouncer
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class NfcTag(
    /** Upper-case hex UID without separators. */
    val uid: String,
    val techs: List<String>,
    /** Text and URI records found in the NDEF message, if any. */
    val ndefTexts: List<String>,
    val timestampMs: Long,
)

enum class NfcAvailability { UNSUPPORTED, DISABLED, ENABLED }

/**
 * NFC card reader using reader mode, which keeps the foreground activity in control
 * (no activity restarts, no system "new tag" sound or chooser).
 */
@Singleton
class NfcReader @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)
    private val debouncer = ScanDebouncer(windowMs = 1_500)

    private val _tags = MutableSharedFlow<NfcTag>(extraBufferCapacity = 8)
    val tags: SharedFlow<NfcTag> = _tags.asSharedFlow()

    val availability: NfcAvailability
        get() = when {
            adapter == null -> NfcAvailability.UNSUPPORTED
            !adapter.isEnabled -> NfcAvailability.DISABLED
            else -> NfcAvailability.ENABLED
        }

    private val callback = NfcAdapter.ReaderCallback { tag -> onTag(tag) }

    /** Call from Activity.onResume. */
    fun enable(activity: Activity) {
        val nfc = adapter ?: return
        if (!nfc.isEnabled) return
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        try {
            nfc.enableReaderMode(activity, callback, READER_FLAGS, extras)
        } catch (e: IllegalStateException) {
            Timber.w(e, "enableReaderMode failed")
        }
    }

    /** Call from Activity.onPause. */
    fun disable(activity: Activity) {
        try {
            adapter?.disableReaderMode(activity)
        } catch (e: IllegalStateException) {
            Timber.w(e, "disableReaderMode failed")
        }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun onTag(tag: Tag) {
        val uid = Hex.encode(tag.id ?: return)
        val accepted = synchronized(debouncer) { debouncer.accept(uid, SystemClock.elapsedRealtime()) }
        if (!accepted) return
        val texts = readNdefTexts(tag)
        _tags.tryEmit(NfcTag(uid, tag.techList.map { it.substringAfterLast('.') }, texts, System.currentTimeMillis()))
    }

    private fun readNdefTexts(tag: Tag): List<String> {
        val ndef = Ndef.get(tag) ?: return emptyList()
        return try {
            ndef.connect()
            val message: NdefMessage? = ndef.ndefMessage ?: ndef.cachedNdefMessage
            message?.records.orEmpty().mapNotNull { decode(it) }
        } catch (e: IOException) {
            Timber.d(e, "NDEF read failed")
            emptyList()
        } catch (e: FormatException) {
            Timber.d(e, "NDEF malformed")
            emptyList()
        } catch (e: SecurityException) {
            Timber.d(e, "tag out of date")
            emptyList()
        } finally {
            try {
                ndef.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun decode(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        return when {
            record.type.contentEquals(NdefRecord.RTD_TEXT) -> NdefRecords.decodeText(record.payload)
            record.type.contentEquals(NdefRecord.RTD_URI) -> NdefRecords.decodeUri(record.payload)
            else -> null
        }
    }

    private companion object {
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
