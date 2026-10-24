package me.longtai.core.hardware.printer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import me.longtai.core.common.print.PlainTextRenderer
import me.longtai.core.common.print.PrintDocument
import me.longtai.core.hardware.settings.HardwareSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

data class SimulatedPrintout(val lines: List<String>, val isLabel: Boolean, val copies: Int)

/** Renders printouts as text so the apps can be demonstrated on devices without a printer. */
@Singleton
class SimulatedPrinter @Inject constructor(
    private val settings: HardwareSettingsRepository,
) : PrinterGateway {

    private val _output = MutableSharedFlow<SimulatedPrintout>(extraBufferCapacity = 4)
    val output: SharedFlow<SimulatedPrintout> = _output.asSharedFlow()

    private val _state = MutableStateFlow<PrinterState>(PrinterState.Simulated)
    override val state: StateFlow<PrinterState> = _state.asStateFlow()

    private var density = 100
    private var learned = false

    override fun connect() = Unit

    override suspend fun print(document: PrintDocument, mode: PrintMode, copies: Int): PrinterResult<Unit> {
        val columns = when (mode) {
            is PrintMode.Label -> (mode.size.widthDots / 12).coerceAtLeast(8)
            PrintMode.Receipt -> settings.settings.value.paperWidth.columns
        }
        delay(250)
        _output.emit(SimulatedPrintout(PlainTextRenderer.render(document, columns), mode is PrintMode.Label, copies))
        return PrinterResult.Ok(Unit)
    }

    override suspend fun checkStatus(): PrinterResult<Unit> = PrinterResult.Ok(Unit)
    override suspend fun feedPaper(px: Int): PrinterResult<Unit> = PrinterResult.Ok(Unit)
    override suspend fun openCashDrawer(): PrinterResult<Unit> = PrinterResult.Ok(Unit)
    override suspend fun getDensity(): PrinterResult<Int> = PrinterResult.Ok(density)
    override suspend fun setDensity(density: Int): PrinterResult<Unit> {
        this.density = density
        return PrinterResult.Ok(Unit)
    }
    override suspend fun setPaperWidth(dots: Int): PrinterResult<Unit> = PrinterResult.Ok(Unit)
    override suspend fun learnLabel(): PrinterResult<Unit> {
        learned = true
        return PrinterResult.Ok(Unit)
    }
    override suspend fun clearLabelLearning(): PrinterResult<Unit> {
        learned = false
        return PrinterResult.Ok(Unit)
    }
    override suspend fun hasLabelLearning(): PrinterResult<Boolean> = PrinterResult.Ok(learned)
    override suspend fun triggerScanHead(open: Boolean): PrinterResult<Unit> = PrinterResult.Ok(Unit)
    override suspend fun info(): PrinterResult<PrinterInfo> = PrinterResult.Ok(PrinterInfo("simulator", "-", "模擬印表機"))
}

/** Routes calls to the real printer or the simulator depending on the settings. */
@Singleton
class PrinterRouter @Inject constructor(
    private val real: NyxPrinterService,
    private val simulated: SimulatedPrinter,
    private val settings: HardwareSettingsRepository,
) : PrinterGateway {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val target: PrinterGateway
        get() = if (settings.settings.value.simulatePrinter) simulated else real

    override val state: StateFlow<PrinterState> = combine(settings.settings, real.state) { s, realState ->
        if (s.simulatePrinter) PrinterState.Simulated else realState
    }.stateIn(scope, SharingStarted.Eagerly, real.state.value)

    override fun connect() = real.connect()
    override suspend fun print(document: PrintDocument, mode: PrintMode, copies: Int) = target.print(document, mode, copies)
    override suspend fun checkStatus() = target.checkStatus()
    override suspend fun feedPaper(px: Int) = target.feedPaper(px)
    override suspend fun openCashDrawer() = target.openCashDrawer()
    override suspend fun getDensity() = target.getDensity()
    override suspend fun setDensity(density: Int) = target.setDensity(density)
    override suspend fun setPaperWidth(dots: Int) = target.setPaperWidth(dots)
    override suspend fun learnLabel() = target.learnLabel()
    override suspend fun clearLabelLearning() = target.clearLabelLearning()
    override suspend fun hasLabelLearning() = target.hasLabelLearning()

    /** The scan head belongs to the real device even when printing is simulated. */
    override suspend fun triggerScanHead(open: Boolean) = real.triggerScanHead(open)
    override suspend fun info() = target.info()
}
