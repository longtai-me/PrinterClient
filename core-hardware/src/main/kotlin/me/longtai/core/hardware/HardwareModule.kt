package me.longtai.core.hardware

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.longtai.core.hardware.nfc.NfcReader
import me.longtai.core.hardware.printer.PrinterGateway
import me.longtai.core.hardware.printer.PrinterRouter
import me.longtai.core.hardware.scanner.ScannerManager
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HardwareModule {
    @Binds
    @Singleton
    abstract fun bindPrinterGateway(impl: PrinterRouter): PrinterGateway
}

/**
 * Wires the device peripherals to an activity's lifecycle: printer binding on create,
 * scan head broadcasts while started, NFC reader mode while resumed.
 * Activities must also forward dispatchKeyEvent to [onKeyEvent] for keyboard-wedge scanners.
 */
@Singleton
class HardwareLifecycle @Inject constructor(
    private val printer: PrinterGateway,
    private val scanner: ScannerManager,
    private val nfc: NfcReader,
) {
    fun attach(activity: ComponentActivity) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = printer.connect()
            override fun onStart(owner: LifecycleOwner) = scanner.register()
            override fun onResume(owner: LifecycleOwner) = nfc.enable(activity)
            override fun onPause(owner: LifecycleOwner) = nfc.disable(activity)
            override fun onStop(owner: LifecycleOwner) = scanner.unregister()
        })
    }

    fun onKeyEvent(event: KeyEvent): Boolean = scanner.onKeyEvent(event)
}
