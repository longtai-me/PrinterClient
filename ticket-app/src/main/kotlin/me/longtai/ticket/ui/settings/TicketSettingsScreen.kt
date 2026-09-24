package me.longtai.ticket.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import me.longtai.core.common.codec.Hex
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.ClickRow
import me.longtai.core.ui.components.ConfirmDialog
import me.longtai.core.ui.components.SectionHeader
import me.longtai.core.ui.components.SwitchRow
import me.longtai.core.ui.components.TextInputDialog
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.ticket.data.PrintTarget
import me.longtai.ticket.data.TicketPrinter
import me.longtai.ticket.data.TicketSettings
import me.longtai.ticket.data.TicketSettingsRepository
import me.longtai.ticket.domain.codec.SignedTicketCodec
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.model.EventProfile
import me.longtai.ticket.domain.model.GatePolicy
import javax.inject.Inject

@HiltViewModel
class TicketSettingsViewModel @Inject constructor(
    private val repository: TicketSettingsRepository,
    private val printer: TicketPrinter,
) : ViewModel() {
    val settings: StateFlow<TicketSettings> = repository.settings
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun update(message: String = "已儲存", transform: (TicketSettings) -> TicketSettings) {
        viewModelScope.launch {
            repository.update(transform)
            _messages.emit(message)
        }
    }

    /** Accepts a raw hex key or the TKKEY1: QR payload printed by another gate. */
    fun importKey(text: String): Boolean {
        val hex = text.trim().removePrefix(TicketPrinter.KEY_PREFIX)
        val bytes = Hex.decode(hex)
        if (bytes == null || bytes.size < 16) {
            _messages.tryEmit("金鑰格式錯誤")
            return false
        }
        update("已匯入金鑰") { it.copy(secretHex = Hex.encode(bytes)) }
        return true
    }

    fun onScan(event: ScanEvent) {
        if (event.code.startsWith(TicketPrinter.KEY_PREFIX)) importKey(event.code)
    }

    fun regenerateKey() = update("已產生新金鑰，舊票券將無法驗證") { it.copy(secretHex = Hex.encode(SignedTicketCodec.generateSecret())) }

    fun printKey() {
        viewModelScope.launch {
            val r = printer.signingKey()
            _messages.emit(if (r is PrinterResult.Error) r.message else "已列印金鑰 QR")
        }
    }
}

@Composable
fun TicketSettingsScreen(
    isAdmin: Boolean,
    onBack: () -> Unit,
    onDevice: () -> Unit,
    onOperators: () -> Unit,
    viewModel: TicketSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var importKey by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmPrintKey by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    if (isAdmin) ScanEffect(viewModel::onScan)

    Scaffold(topBar = { AppTopBar("設定", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding()) {
            item { ClickRow("裝置設定", "印表機、標籤紙、掃描器、NFC", Icons.Filled.Devices, onClick = onDevice) }
            if (!isAdmin) {
                item { Text("活動、閘口與金鑰設定僅限管理員。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { ClickRow("人員管理", "新增驗票員、重設 PIN", Icons.Filled.AdminPanelSettings, onClick = onOperators) }
                item { HorizontalDivider() }
                item { SectionHeader("活動資料（列印於票券）") }
                item { EventEditor(settings.event) { e -> viewModel.update { it.copy(event = e) } } }
                item { HorizontalDivider() }
                item { SectionHeader("閘口規則") }
                item { GateEditor(settings.gate) { g -> viewModel.update { it.copy(gate = g) } } }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("預設方向", Modifier.padding(top = 8.dp))
                        Direction.entries.forEach { d ->
                            FilterChip(selected = settings.defaultDirection == d, onClick = { viewModel.update { it.copy(defaultDirection = d) } }, label = { Text(d.label) })
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("結果顯示", Modifier.padding(top = 8.dp))
                        listOf(2, 3, 5, 0).forEach { sec ->
                            FilterChip(
                                selected = settings.resultSeconds == sec,
                                onClick = { viewModel.update { it.copy(resultSeconds = sec) } },
                                label = { Text(if (sec == 0) "常駐" else "${sec}秒") },
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("通過後列印", Modifier.padding(top = 8.dp))
                        PrintTarget.entries.forEach { t ->
                            FilterChip(selected = settings.printOnAccept == t, onClick = { viewModel.update { it.copy(printOnAccept = t) } }, label = { Text(t.label) })
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("發行票券紙材", Modifier.padding(top = 8.dp))
                        listOf(PrintTarget.RECEIPT, PrintTarget.LABEL).forEach { t ->
                            FilterChip(selected = settings.issueTarget == t, onClick = { viewModel.update { it.copy(issueTarget = t) } }, label = { Text(t.label) })
                        }
                    }
                }
                item { HorizontalDivider() }
                item { SectionHeader("票券安全（離線簽章驗證）") }
                item {
                    SwitchRow(
                        "發行簽章 QR 票券",
                        settings.signedTickets,
                        { v -> viewModel.update { it.copy(signedTickets = v) } },
                        "共用同一把金鑰的驗票機，不需連網即可驗證彼此發行的票券",
                    )
                }
                item { ClickRow("金鑰指紋", settings.keyFingerprint ?: "產生中…", Icons.Filled.Key) {} }
                item { ClickRow("列印金鑰 QR", "供其他驗票機掃描匯入", Icons.Filled.Print) { confirmPrintKey = true } }
                item { ClickRow("匯入金鑰", "在此畫面掃描金鑰 QR，或手動輸入十六進位金鑰") { importKey = true } }
                item { ClickRow("產生新金鑰", "舊金鑰簽發的票券將無法驗證") { confirmRegenerate = true } }
            }
            item { HorizontalDivider() }
            item { ClickRow("關於", "票券驗票 1.0.0 · 離線運作，資料儲存在本機", Icons.Filled.Info) {} }
        }
    }

    if (importKey) {
        TextInputDialog(
            title = "匯入金鑰",
            label = "十六進位金鑰或 TKKEY1: 內容",
            onConfirm = { if (viewModel.importKey(it)) importKey = false },
            onDismiss = { importKey = false },
        )
    }
    if (confirmRegenerate) {
        ConfirmDialog(
            title = "產生新金鑰",
            message = "產生後，以舊金鑰簽發的票券在本機將驗證失敗，其他驗票機也需重新匯入。確定要繼續嗎？",
            confirmText = "產生",
            destructive = true,
            onConfirm = {
                confirmRegenerate = false
                viewModel.regenerateKey()
            },
            onDismiss = { confirmRegenerate = false },
        )
    }
    if (confirmPrintKey) {
        ConfirmDialog(
            title = "列印金鑰",
            message = "持有金鑰者可偽造票券。請僅在設定其他驗票機時列印，匯入後立即銷毀紙本。",
            confirmText = "列印",
            onConfirm = {
                confirmPrintKey = false
                viewModel.printKey()
            },
            onDismiss = { confirmPrintKey = false },
        )
    }
}

@Composable
private fun EventEditor(event: EventProfile, onSave: (EventProfile) -> Unit) {
    var name by remember(event) { mutableStateOf(event.name) }
    var subtitle by remember(event) { mutableStateOf(event.subtitle.orEmpty()) }
    var footer by remember(event) { mutableStateOf(event.footer.orEmpty()) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("活動 / 場館名稱") }, singleLine = true)
        OutlinedTextField(subtitle, { subtitle = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("副標題（地點、日期）") }, singleLine = true)
        OutlinedTextField(footer, { footer = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("票券頁尾說明") }, singleLine = true)
        Button(onClick = { onSave(EventProfile(name, subtitle.ifBlank { null }, footer.ifBlank { null })) }) { Text("儲存活動資料") }
    }
}

@Composable
private fun GateEditor(gate: GatePolicy, onSave: (GatePolicy) -> Unit) {
    var name by remember(gate) { mutableStateOf(gate.gateName) }
    var zone by remember(gate) { mutableStateOf(gate.zone.orEmpty()) }
    var passback by remember(gate) { mutableStateOf(gate.antiPassbackMinutes.toString()) }
    var requireExit by remember(gate) { mutableStateOf(gate.requireExitBeforeReentry) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(20) }, Modifier.weight(1f), label = { Text("閘口名稱") }, singleLine = true)
            OutlinedTextField(zone, { zone = it.take(20) }, Modifier.weight(1f), label = { Text("本閘口區域（空白=不限）") }, singleLine = true)
        }
        OutlinedTextField(
            passback, { passback = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
            label = { Text("防回傳：同票再次入場需間隔（分鐘，0=不限制）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        SwitchRow("須先出場才能再次入場", requireExit, { requireExit = it }, "適用門禁/場館人數控管")
        Button(onClick = {
            onSave(GatePolicy(name.ifBlank { "閘口 1" }, zone.ifBlank { null }, passback.toIntOrNull() ?: 0, requireExit))
        }) { Text("儲存閘口規則") }
    }
}
