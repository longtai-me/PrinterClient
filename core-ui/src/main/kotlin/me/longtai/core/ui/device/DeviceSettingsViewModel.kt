package me.longtai.core.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.longtai.core.common.print.Align
import me.longtai.core.common.print.FontSize
import me.longtai.core.common.print.LabelSize
import me.longtai.core.common.print.PaperWidth
import me.longtai.core.common.print.printDocument
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.hardware.Hardware
import me.longtai.core.hardware.nfc.NfcAvailability
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.hardware.printer.PrintMode
import me.longtai.core.hardware.printer.PrinterInfo
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.hardware.printer.PrinterState
import me.longtai.core.hardware.settings.HardwareSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DeviceSettingsViewModel @Inject constructor(
    private val hardware: Hardware,
) : ViewModel() {

    val settings: StateFlow<HardwareSettings> = hardware.settings.settings
    val printerState: StateFlow<PrinterState> = hardware.printer.state

    private val _density = MutableStateFlow<Int?>(null)
    val density: StateFlow<Int?> = _density.asStateFlow()

    private val _info = MutableStateFlow<PrinterInfo?>(null)
    val info: StateFlow<PrinterInfo?> = _info.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastScan = MutableStateFlow<String?>(null)
    val lastScan: StateFlow<String?> = _lastScan.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val nfcAvailability: NfcAvailability get() = hardware.nfc.availability
    val cameraScannerInstalled: Boolean get() = hardware.scanner.isCameraScannerInstalled

    init {
        refresh()
    }

    fun refresh() = launchBusy {
        (hardware.printer.info() as? PrinterResult.Ok)?.let { _info.value = it.value }
        (hardware.printer.getDensity() as? PrinterResult.Ok)?.let { _density.value = it.value }
    }

    fun setPaperWidth(width: PaperWidth) = launchBusy {
        hardware.settings.update { it.copy(paperWidth = width) }
        report(hardware.printer.setPaperWidth(width.dots), "紙寬已設為 ${width.mm}mm")
    }

    fun setDensity(value: Int) = launchBusy {
        val result = hardware.printer.setDensity(value)
        if (result.isOk) _density.value = value
        report(result, "列印濃度已設為 $value%")
    }

    fun setLabelSize(widthMm: Int?, heightMm: Int?, gapMm: Int?) = launchBusy {
        val size = runCatching { LabelSize(widthMm ?: -1, heightMm ?: -1, gapMm ?: -1) }.getOrNull()
        if (size == null) {
            _messages.emit("標籤尺寸無效：寬 10–80mm、高 10–200mm、間距 0–20mm")
        } else {
            hardware.settings.update { it.copy(labelSize = size) }
            _messages.emit("標籤尺寸已儲存")
        }
    }

    fun setLabelAutoLocate(enabled: Boolean) = launchBusy { hardware.settings.update { it.copy(labelAutoLocate = enabled) } }

    fun learnLabel() = launchBusy { report(hardware.printer.learnLabel(), "標籤學習完成") }

    fun clearLabelLearning() = launchBusy { report(hardware.printer.clearLabelLearning(), "已清除標籤學習資料") }

    fun setCashDrawer(enabled: Boolean) = launchBusy { hardware.settings.update { it.copy(cashDrawerEnabled = enabled) } }

    fun testCashDrawer() = launchBusy { report(hardware.printer.openCashDrawer(), "已送出開錢箱指令") }

    fun setSimulate(enabled: Boolean) = launchBusy { hardware.settings.update { it.copy(simulatePrinter = enabled) } }

    fun setSound(enabled: Boolean) = launchBusy { hardware.settings.update { it.copy(soundEnabled = enabled) } }

    fun setVibration(enabled: Boolean) = launchBusy { hardware.settings.update { it.copy(vibrationEnabled = enabled) } }

    fun setKeyboardWedge(enabled: Boolean) = launchBusy { hardware.settings.update { it.copy(keyboardWedgeEnabled = enabled) } }

    fun testReceipt() = launchBusy {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date())
        val doc = printDocument {
            title("列印測試")
            center(now)
            divider()
            pair("紙寬", "${settings.value.paperWidth.mm}mm")
            pair("濃度", density.value?.let { "$it%" } ?: "-")
            text("中文 English 12345", size = FontSize.NORMAL)
            text("大字體", size = FontSize.XLARGE, bold = true)
            divider()
            barcode("1234567890128")
            qrCode("https://example.com/print-test", sizePx = 200)
            center("—— 測試完成 ——")
        }
        report(hardware.printer.print(doc), "已送出測試列印")
    }

    fun testLabel() = launchBusy {
        val s = settings.value
        val doc = printDocument {
            text("標籤測試 ${s.labelSize.widthMm}×${s.labelSize.heightMm}mm", Align.LEFT, FontSize.NORMAL, bold = true)
            text("$123", Align.RIGHT, FontSize.XLARGE, bold = true)
            barcode("TEST-0001", heightPx = 60)
        }
        report(hardware.printer.print(doc, PrintMode.Label(s.labelSize, s.labelAutoLocate)), "已送出標籤測試")
    }

    fun triggerScanHead() = launchBusy { report(hardware.scanner.startScanHead(), "請對準條碼") }

    fun onScan(event: ScanEvent) {
        hardware.feedback.success()
        _lastScan.value = "${event.source.label()}：${event.code}"
    }

    fun onNfc(tag: NfcTag) {
        hardware.feedback.success()
        _lastScan.value = "NFC：${tag.uid}" + if (tag.ndefTexts.isNotEmpty()) "（${tag.ndefTexts.joinToString()}）" else ""
    }

    private fun me.longtai.core.common.scan.ScanSource.label() = when (this) {
        me.longtai.core.common.scan.ScanSource.SCAN_HEAD -> "掃描頭"
        me.longtai.core.common.scan.ScanSource.CAMERA -> "相機"
        me.longtai.core.common.scan.ScanSource.KEYBOARD -> "鍵盤掃描器"
        me.longtai.core.common.scan.ScanSource.NFC -> "NFC"
        me.longtai.core.common.scan.ScanSource.MANUAL -> "手動"
    }

    private suspend fun report(result: PrinterResult<*>, success: String) {
        _messages.emit(
            when (result) {
                is PrinterResult.Ok -> success
                is PrinterResult.Error -> result.message
            },
        )
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
