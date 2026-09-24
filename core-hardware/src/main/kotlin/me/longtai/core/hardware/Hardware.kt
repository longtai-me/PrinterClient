package me.longtai.core.hardware

import me.longtai.core.hardware.feedback.Feedback
import me.longtai.core.hardware.nfc.NfcReader
import me.longtai.core.hardware.printer.PrinterGateway
import me.longtai.core.hardware.printer.SimulatedPrinter
import me.longtai.core.hardware.scanner.ScannerManager
import me.longtai.core.hardware.settings.HardwareSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** All device peripherals, provided to the UI as one object. */
@Singleton
class Hardware @Inject constructor(
    val printer: PrinterGateway,
    val scanner: ScannerManager,
    val nfc: NfcReader,
    val feedback: Feedback,
    val settings: HardwareSettingsRepository,
    val simulatedPrinter: SimulatedPrinter,
)
