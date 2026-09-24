package me.longtai.core.hardware.printer

import kotlinx.coroutines.flow.StateFlow
import me.longtai.core.common.print.LabelSize
import me.longtai.core.common.print.PrintDocument
import me.longtai.core.common.printer.PrinterResultCodes

sealed interface PrinterState {
    /** The vendor printer service is not installed on this device. */
    data object Unavailable : PrinterState
    data object Connecting : PrinterState
    data object Disconnected : PrinterState
    data class Connected(val info: PrinterInfo?) : PrinterState

    /** Printouts are shown on screen instead of printed. */
    data object Simulated : PrinterState
}

data class PrinterInfo(
    val serviceVersion: String?,
    val firmwareVersion: String?,
    val model: String?,
)

sealed interface PrinterResult<out T> {
    data class Ok<T>(val value: T) : PrinterResult<T>

    data class Error(val code: Int) : PrinterResult<Nothing> {
        val message: String get() = PrinterResultCodes.describe(code)

        /** Paper, cover, heat or battery: the operator can fix it and retry. */
        val recoverable: Boolean get() = PrinterResultCodes.isRecoverableByOperator(code)
    }

    val isOk: Boolean get() = this is Ok
    fun errorOrNull(): Error? = this as? Error
}

sealed interface PrintMode {
    /** Continuous receipt paper; paper is fed to the tear-off position afterwards. */
    data object Receipt : PrintMode

    /** Gap label stock: locate the next label before printing, end at the tear-off edge. */
    data class Label(val size: LabelSize, val autoLocate: Boolean) : PrintMode
}

/** Printer operations; every call is serialised and executed off the main thread. */
interface PrinterGateway {
    val state: StateFlow<PrinterState>

    /** Binds the printer service. Safe to call repeatedly. */
    fun connect()

    suspend fun print(document: PrintDocument, mode: PrintMode = PrintMode.Receipt, copies: Int = 1): PrinterResult<Unit>
    suspend fun checkStatus(): PrinterResult<Unit>
    suspend fun feedPaper(px: Int): PrinterResult<Unit>
    suspend fun openCashDrawer(): PrinterResult<Unit>
    suspend fun getDensity(): PrinterResult<Int>
    suspend fun setDensity(density: Int): PrinterResult<Unit>
    suspend fun setPaperWidth(dots: Int): PrinterResult<Unit>
    suspend fun learnLabel(): PrinterResult<Unit>
    suspend fun clearLabelLearning(): PrinterResult<Unit>
    suspend fun hasLabelLearning(): PrinterResult<Boolean>

    /** Opens (true) or closes (false) the built-in scan head; results arrive by broadcast. */
    suspend fun triggerScanHead(open: Boolean): PrinterResult<Unit>
    suspend fun info(): PrinterResult<PrinterInfo>
}
