package me.longtai.core.hardware.scanner

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.longtai.core.common.scan.KeyboardWedgeDecoder
import me.longtai.core.common.scan.ScanDebouncer
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.common.scan.ScanSource
import me.longtai.core.hardware.printer.PrinterGateway
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.hardware.settings.HardwareSettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single stream of barcode/QR scans from every input the device offers:
 * the built-in scan head (broadcast), the vendor camera scanner app, keyboard-wedge
 * scanners and manual entry.
 */
@Singleton
class ScannerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val printer: PrinterGateway,
    private val settings: HardwareSettingsRepository,
) {
    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    private val debouncer = ScanDebouncer()
    private val wedge = KeyboardWedgeDecoder()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SCAN_HEAD) return
            val code = intent.getStringExtra(EXTRA_SCAN_HEAD) ?: return
            emit(code, ScanSource.SCAN_HEAD)
        }
    }

    /** Starts listening for scan head results; call while an activity is started. */
    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SCAN_HEAD), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        wedge.reset()
    }

    /** Fires the scan head (same as pressing the device's scan key). */
    suspend fun startScanHead(): PrinterResult<Unit> = printer.triggerScanHead(true)

    suspend fun stopScanHead(): PrinterResult<Unit> = printer.triggerScanHead(false)

    @Synchronized
    fun emit(code: String, source: ScanSource) {
        val clean = code.trim().trim('\r', '\n', '\u0000')
        if (clean.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (source != ScanSource.MANUAL && !debouncer.accept(clean, now)) return
        Timber.d("scan %s: %s", source, clean)
        _events.tryEmit(ScanEvent(clean, source, System.currentTimeMillis()))
    }

    /**
     * Feed every key event from Activity.dispatchKeyEvent. Returns true when the event
     * completed a keyboard-wedge scan and should be consumed.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!settings.settings.value.keyboardWedgeEnabled || event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val code = wedge.onEnter(event.eventTime) ?: return false
            emit(code, ScanSource.KEYBOARD)
            return true
        }
        val unicode = event.unicodeChar
        if (unicode > 0) wedge.onChar(unicode.toChar(), event.eventTime)
        return false
    }

    val isCameraScannerInstalled: Boolean
        get() = try {
            context.packageManager.getPackageInfo(CAMERA_SCANNER_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Launches the vendor camera scanner app and returns its result. */
    class CameraScanContract : ActivityResultContract<String, String?>() {
        override fun createIntent(context: Context, input: String): Intent = Intent()
            .setComponent(ComponentName(CAMERA_SCANNER_PACKAGE, CAMERA_SCANNER_ACTIVITY))
            .putExtra("TITLE", input)

        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            if (resultCode == Activity.RESULT_OK) intent?.getStringExtra("SCAN_RESULT") else null
    }

    companion object {
        const val ACTION_SCAN_HEAD = "com.android.NYX_QSC_DATA"
        const val EXTRA_SCAN_HEAD = "qsc"
        const val CAMERA_SCANNER_PACKAGE = "net.nyx.scanner"
        const val CAMERA_SCANNER_ACTIVITY = "net.nyx.scanner.ScannerActivity"
    }
}
