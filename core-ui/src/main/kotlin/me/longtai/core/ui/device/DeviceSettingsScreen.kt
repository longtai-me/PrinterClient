package me.longtai.core.ui.device

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.longtai.core.common.print.PaperWidth
import me.longtai.core.hardware.nfc.NfcAvailability
import me.longtai.core.hardware.printer.PrinterState
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.ClickRow
import me.longtai.core.ui.components.PrinterStatusChip
import me.longtai.core.ui.components.SectionHeader
import me.longtai.core.ui.components.SwitchRow
import me.longtai.core.ui.hardware.NfcEffect
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.core.ui.hardware.rememberCameraScanner

private val DENSITY_58 = listOf(80, 90, 100, 110, 120, 130)
private val DENSITY_80 = listOf(100, 110, 120, 130)

/** Printer, label, scanner and NFC configuration shared by both apps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceSettingsScreen(onBack: () -> Unit, viewModel: DeviceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val printerState by viewModel.printerState.collectAsStateWithLifecycle()
    val density by viewModel.density.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val lastScan by viewModel.lastScan.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val openCamera = rememberCameraScanner()

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    ScanEffect(viewModel::onScan)
    NfcEffect(viewModel::onNfc)

    Scaffold(
        topBar = {
            AppTopBar("裝置設定", onBack = onBack) {
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }

            item { SectionHeader("印表機") }
            item {
                ListItem(
                    headlineContent = { PrinterStatusChip(printerState) },
                    supportingContent = {
                        val i = info
                        Text(
                            if (i == null) "尚未取得印表機資訊"
                            else "服務 ${i.serviceVersion ?: "-"} · 韌體 ${i.firmwareVersion ?: "-"} · 型號 ${i.model ?: "-"}",
                        )
                    },
                )
            }
            item {
                SwitchRow(
                    title = "模擬列印",
                    subtitle = "無印表機時以畫面預覽列印內容",
                    checked = settings.simulatePrinter,
                    onCheckedChange = viewModel::setSimulate,
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("紙張寬度", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperWidth.entries.forEach { w ->
                            FilterChip(
                                selected = settings.paperWidth == w,
                                onClick = { viewModel.setPaperWidth(w) },
                                label = { Text("${w.mm}mm") },
                            )
                        }
                    }
                    Text("列印濃度" + (density?.let { "（目前 $it%）" } ?: ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (settings.paperWidth == PaperWidth.MM58) DENSITY_58 else DENSITY_80).forEach { d ->
                            FilterChip(selected = density == d, onClick = { viewModel.setDensity(d) }, label = { Text("$d%") })
                        }
                    }
                }
            }
            item { ClickRow("列印測試頁", icon = Icons.Filled.Print, enabled = !busy, onClick = viewModel::testReceipt) }

            item { HorizontalDivider() }
            item { SectionHeader("標籤紙") }
            item { LabelSizeEditor(settings.labelSize.widthMm, settings.labelSize.heightMm, settings.labelSize.gapMm, viewModel::setLabelSize) }
            item {
                SwitchRow(
                    title = "自動定位標籤",
                    subtitle = "需先執行標籤學習；關閉則使用上方尺寸定位",
                    checked = settings.labelAutoLocate,
                    onCheckedChange = viewModel::setLabelAutoLocate,
                )
            }
            item { ClickRow("標籤學習", "放入標籤紙後執行，印表機會自動偵測間距", Icons.Filled.School, !busy, viewModel::learnLabel) }
            item { ClickRow("清除標籤學習資料", enabled = !busy, onClick = viewModel::clearLabelLearning) }
            item { ClickRow("列印測試標籤", icon = Icons.Filled.Sell, enabled = !busy, onClick = viewModel::testLabel) }

            item { HorizontalDivider() }
            item { SectionHeader("錢箱") }
            item { SwitchRow("現金結帳時開啟錢箱", settings.cashDrawerEnabled, viewModel::setCashDrawer) }
            item { ClickRow("測試開錢箱", icon = Icons.Filled.PointOfSale, enabled = !busy, onClick = viewModel::testCashDrawer) }

            item { HorizontalDivider() }
            item { SectionHeader("掃描與 NFC") }
            item {
                ListItem(
                    headlineContent = { Text("最近一次讀取") },
                    supportingContent = { Text(lastScan ?: "請掃描條碼或感應 NFC 卡片測試") },
                )
            }
            item { ClickRow("觸發掃描頭", icon = Icons.Filled.QrCodeScanner, onClick = viewModel::triggerScanHead) }
            item {
                ClickRow(
                    "相機掃描",
                    if (viewModel.cameraScannerInstalled) null else "未安裝掃描 App（net.nyx.scanner）",
                    Icons.Filled.CameraAlt,
                    viewModel.cameraScannerInstalled,
                ) { openCamera() }
            }
            item {
                val nfc = viewModel.nfcAvailability
                ClickRow(
                    title = "NFC 狀態",
                    subtitle = when (nfc) {
                        NfcAvailability.UNSUPPORTED -> "此裝置不支援 NFC"
                        NfcAvailability.DISABLED -> "NFC 已關閉，點此開啟系統設定"
                        NfcAvailability.ENABLED -> "NFC 已啟用，可感應卡片"
                    },
                    icon = Icons.Filled.Nfc,
                    enabled = nfc == NfcAvailability.DISABLED,
                ) {
                    try {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }
            item { SwitchRow("鍵盤式掃描器", settings.keyboardWedgeEnabled, viewModel::setKeyboardWedge, "外接 USB/藍牙掃描槍（模擬鍵盤輸入）") }

            item { HorizontalDivider() }
            item { SectionHeader("提示") }
            item { SwitchRow("提示音", settings.soundEnabled, viewModel::setSound) }
            item { SwitchRow("震動", settings.vibrationEnabled, viewModel::setVibration) }
            item {
                if (printerState == PrinterState.Unavailable) {
                    Text(
                        "此裝置未安裝印表機服務（net.nyx.printerservice）。可開啟「模擬列印」在一般手機上測試流程。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelSizeEditor(widthMm: Int, heightMm: Int, gapMm: Int, onSave: (Int?, Int?, Int?) -> Unit) {
    var w by remember(widthMm) { mutableStateOf(widthMm.toString()) }
    var h by remember(heightMm) { mutableStateOf(heightMm.toString()) }
    var g by remember(gapMm) { mutableStateOf(gapMm.toString()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
            OutlinedTextField(w, { w = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("寬 mm") }, singleLine = true, keyboardOptions = numbers)
            OutlinedTextField(h, { h = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("高 mm") }, singleLine = true, keyboardOptions = numbers)
            OutlinedTextField(g, { g = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("間距 mm") }, singleLine = true, keyboardOptions = numbers)
        }
        Button(onClick = { onSave(w.toIntOrNull(), h.toIntOrNull(), g.toIntOrNull()) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("儲存標籤尺寸")
        }
    }
}
