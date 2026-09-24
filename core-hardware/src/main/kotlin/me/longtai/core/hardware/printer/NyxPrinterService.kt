package me.longtai.core.hardware.printer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.os.RemoteException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.longtai.core.common.print.BarcodeSymbology
import me.longtai.core.common.print.DividerStyle
import me.longtai.core.common.print.FontSize
import me.longtai.core.common.print.PrintDocument
import me.longtai.core.common.print.PrintElement
import me.longtai.core.common.printer.PrinterResultCodes
import me.longtai.core.hardware.settings.HardwareSettingsRepository
import net.nyx.printerservice.print.IPrinterService
import net.nyx.printerservice.print.PrintTextFormat
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PrinterGateway] backed by the vendor AIDL service `net.nyx.printerservice`.
 *
 * Calls are serialised with a mutex so that concurrent print jobs never interleave,
 * and executed on the IO dispatcher because binder calls block until the printer is done.
 */
@Singleton
class NyxPrinterService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: HardwareSettingsRepository,
) : PrinterGateway {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val service = MutableStateFlow<IPrinterService?>(null)
    private val _state = MutableStateFlow<PrinterState>(PrinterState.Disconnected)
    override val state: StateFlow<PrinterState> = _state.asStateFlow()

    private var bound = false
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = INITIAL_RETRY_MS
    private val dividerCache = HashMap<Pair<Int, DividerStyle>, Bitmap>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IPrinterService.Stub.asInterface(binder)
            service.value = svc
            reconnectDelayMs = INITIAL_RETRY_MS
            _state.value = PrinterState.Connected(null)
            mainScope.launch {
                val info = info()
                if (info is PrinterResult.Ok && service.value === svc) _state.value = PrinterState.Connected(info.value)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The binding stays registered; the system reconnects when the service restarts.
            service.value = null
            _state.value = PrinterState.Disconnected
        }

        override fun onBindingDied(name: ComponentName?) {
            service.value = null
            unbind()
            _state.value = PrinterState.Disconnected
            scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName?) {
            unbind()
            _state.value = PrinterState.Unavailable
        }
    }

    override fun connect() {
        mainScope.launch { bind() }
    }

    private fun bind() {
        if (bound) return
        val intent = Intent(ACTION).setPackage(PACKAGE)
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Timber.w(e, "bindService rejected")
            false
        }
        if (bound) {
            if (service.value == null) _state.value = PrinterState.Connecting
        } else {
            _state.value = PrinterState.Unavailable
            scheduleReconnect()
        }
    }

    private fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = mainScope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
            bind()
        }
    }

    private suspend fun awaitService(): IPrinterService? {
        service.value?.let { return it }
        connect()
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { service.filterNotNull().first() }
    }

    private suspend fun <T> call(block: (IPrinterService) -> PrinterResult<T>): PrinterResult<T> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val svc = awaitService()
            if (svc == null) {
                val code = if (_state.value == PrinterState.Unavailable) {
                    PrinterResultCodes.APP_SERVICE_UNAVAILABLE
                } else {
                    PrinterResultCodes.DEVICE_NOT_CONNECT
                }
                PrinterResult.Error(code)
            } else {
                try {
                    block(svc)
                } catch (e: RemoteException) {
                    Timber.e(e, "printer service call failed")
                    PrinterResult.Error(PrinterResultCodes.APP_REMOTE_EXCEPTION)
                } catch (e: RuntimeException) {
                    Timber.e(e, "printer service call failed")
                    PrinterResult.Error(PrinterResultCodes.SDK_UNKNOWN_ERR)
                }
            }
        }
    }

    private fun result(code: Int): PrinterResult<Unit> =
        if (code == PrinterResultCodes.OK) PrinterResult.Ok(Unit) else PrinterResult.Error(code)

    override suspend fun print(document: PrintDocument, mode: PrintMode, copies: Int): PrinterResult<Unit> = call { svc ->
        val status = svc.printerStatus
        if (status != PrinterResultCodes.OK) return@call PrinterResult.Error(status)
        repeat(copies.coerceIn(1, MAX_COPIES)) {
            val ret = printOnce(svc, document, mode)
            if (ret != PrinterResultCodes.OK) return@call PrinterResult.Error(ret)
        }
        PrinterResult.Ok(Unit)
    }

    private fun printOnce(svc: IPrinterService, document: PrintDocument, mode: PrintMode): Int {
        val paperDots = settings.settings.value.paperWidth.dots
        val widthDots = when (mode) {
            is PrintMode.Label -> mode.size.widthDots.coerceAtMost(paperDots)
            PrintMode.Receipt -> paperDots
        }
        if (mode is PrintMode.Label) {
            val height = mode.size.heightDots
            val gap = mode.size.gapDots
            val located = if (mode.autoLocate && svc.hasLabelLearning()) svc.labelLocateAuto(height, gap) else svc.labelLocate(height, gap)
            if (located != PrinterResultCodes.OK) return located
        }
        for (element in document.elements) {
            val ret = render(svc, element, widthDots)
            if (ret != PrinterResultCodes.OK) return ret
        }
        return when (mode) {
            is PrintMode.Label -> svc.labelPrintEnd()
            PrintMode.Receipt -> svc.printEndAutoOut()
        }
    }

    private fun render(svc: IPrinterService, element: PrintElement, widthDots: Int): Int = when (element) {
        is PrintElement.Text -> svc.printText(element.text, textFormat(element.size, element.bold, element.align.ordinal, element.underline))
        is PrintElement.Row -> svc.printTableText(
            element.cells.map { it.text }.toTypedArray(),
            element.cells.map { it.weight }.toIntArray(),
            element.cells.map { textFormat(element.size, element.bold, it.align.ordinal, false) }.toTypedArray(),
        )
        is PrintElement.Divider -> svc.printBitmap(divider(widthDots, element.style), 0, 1)
        is PrintElement.Barcode -> {
            val width = if (element.widthPx > 0) element.widthPx.coerceAtMost(widthDots) else autoBarcodeWidth(element, widthDots)
            svc.printBarcode(element.content, width, element.heightPx, element.hri.code, element.align.ordinal, element.symbology.code)
        }
        is PrintElement.QrCode -> {
            val size = element.sizePx.coerceAtMost(widthDots)
            svc.printQrCode(element.content, size, size, element.align.ordinal)
        }
        is PrintElement.Feed -> if (element.px > 0) svc.paperOut(element.px) else PrinterResultCodes.OK
    }

    private fun textFormat(size: FontSize, bold: Boolean, align: Int, underline: Boolean) = PrintTextFormat().apply {
        setTextSize(size.px)
        setStyle(if (bold) 1 else 0)
        setAli(align)
        setUnderline(underline)
    }

    private fun autoBarcodeWidth(element: PrintElement.Barcode, maxWidth: Int): Int {
        val modules = when (element.symbology) {
            BarcodeSymbology.EAN13, BarcodeSymbology.UPC_A -> 95
            BarcodeSymbology.EAN8 -> 67
            BarcodeSymbology.UPC_E -> 51
            BarcodeSymbology.CODE39 -> element.content.length * 16 + 32
            else -> 11 * (element.content.length + 3) + 2
        }
        val max = (maxWidth - 16).coerceAtLeast(64)
        return (modules * 2).coerceIn(minOf(160, max), max)
    }

    private fun divider(width: Int, style: DividerStyle): Bitmap = synchronized(dividerCache) {
        dividerCache.getOrPut(width to style) {
            val height = if (style == DividerStyle.DOUBLE) 8 else 4
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            fun line(y: Int, dashed: Boolean) {
                for (x in 0 until width) if (!dashed || x % 12 < 8) bitmap.setPixel(x, y, Color.BLACK)
            }
            when (style) {
                DividerStyle.DASHED -> {
                    line(1, true)
                    line(2, true)
                }
                DividerStyle.SOLID -> {
                    line(1, false)
                    line(2, false)
                }
                DividerStyle.DOUBLE -> {
                    line(1, false)
                    line(2, false)
                    line(5, false)
                    line(6, false)
                }
            }
            bitmap
        }
    }

    override suspend fun checkStatus() = call { result(it.printerStatus) }

    override suspend fun feedPaper(px: Int) = call { result(it.paperOut(px)) }

    override suspend fun openCashDrawer() = call { result(it.openCashBox()) }

    override suspend fun getDensity(): PrinterResult<Int> = call { svc ->
        val out = IntArray(1)
        val ret = svc.getPrinterDensity(out)
        if (ret == PrinterResultCodes.OK) PrinterResult.Ok(out[0]) else PrinterResult.Error(ret)
    }

    override suspend fun setDensity(density: Int) = call { result(it.setPrinterDensity(density)) }

    override suspend fun setPaperWidth(dots: Int) = call { result(it.setPaperWidth(dots)) }

    override suspend fun learnLabel() = call { result(it.labelDetectAuto()) }

    override suspend fun clearLabelLearning() = call { result(it.clearLabelLearning()) }

    override suspend fun hasLabelLearning(): PrinterResult<Boolean> = call { PrinterResult.Ok(it.hasLabelLearning()) }

    override suspend fun triggerScanHead(open: Boolean) = call { result(it.triggerQscScan(if (open) 0 else 1)) }

    override suspend fun info(): PrinterResult<PrinterInfo> = call { svc ->
        val version = arrayOfNulls<String>(1)
        val model = arrayOfNulls<String>(1)
        val serviceVersion = runCatching { svc.serviceVersion }.getOrNull()
        runCatching { svc.getPrinterVersion(version) }
        runCatching { svc.getPrinterModel(model) }
        PrinterResult.Ok(PrinterInfo(serviceVersion, version[0], model[0]))
    }

    private companion object {
        const val PACKAGE = "net.nyx.printerservice"
        const val ACTION = "net.nyx.printerservice.IPrinterService"
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 60_000L
        const val MAX_COPIES = 10
    }
}
