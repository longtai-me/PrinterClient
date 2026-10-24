package me.longtai.ticket.ui.redeem

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.core.auth.Operator
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.common.scan.ScanSource
import me.longtai.core.hardware.feedback.Feedback
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.hardware.printer.PrinterGateway
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.hardware.printer.PrinterState
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.TextInputDialog
import me.longtai.core.ui.hardware.LocalHardware
import me.longtai.core.ui.hardware.NfcEffect
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.core.ui.hardware.rememberCameraScanner
import me.longtai.core.ui.theme.StatusColors
import me.longtai.ticket.data.RedemptionResult
import me.longtai.ticket.data.RedemptionService
import me.longtai.ticket.data.TicketPrinter
import me.longtai.ticket.data.TicketRepository
import me.longtai.ticket.data.TicketSettings
import me.longtai.ticket.data.TicketSettingsRepository
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.print.GateStats
import me.longtai.ticket.domain.validation.Decision
import me.longtai.ticket.domain.validation.DecisionText
import me.longtai.ticket.ui.Routes
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

data class ScanResultUi(
    val id: Long,
    val accepted: Boolean,
    val direction: Direction,
    val title: String,
    val detail: String?,
    val code: String,
    val enrolled: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RedeemViewModel @Inject constructor(
    private val service: RedemptionService,
    repository: TicketRepository,
    settingsRepository: TicketSettingsRepository,
    private val feedback: Feedback,
    private val ticketPrinter: TicketPrinter,
    printer: PrinterGateway,
) : ViewModel() {
    val settings: StateFlow<TicketSettings> = settingsRepository.settings
    val printerState: StateFlow<PrinterState> = printer.state

    private val _direction = MutableStateFlow(settings.value.defaultDirection)
    val direction: StateFlow<Direction> = _direction.asStateFlow()

    /** Re-evaluated every minute so the counters roll over at midnight. */
    val stats: StateFlow<GateStats> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(60_000)
        }
    }.distinctUntilChanged()
        .flatMapLatest { repository.stats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateStats(0, 0, 0, 0, 0))

    private val _result = MutableStateFlow<ScanResultUi?>(null)
    val result: StateFlow<ScanResultUi?> = _result.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setDirection(direction: Direction) {
        _direction.value = direction
        _result.value = null
    }

    fun onScan(event: ScanEvent) = process { service.redeem(event.code, event.source, _direction.value) }

    fun onNfc(tag: NfcTag) = process { service.redeemNfc(tag, _direction.value) }

    fun manual(code: String) = process { service.redeem(code, ScanSource.MANUAL, _direction.value) }

    fun dismiss(id: Long) {
        if (_result.value?.id == id) _result.value = null
    }

    private fun process(block: suspend () -> RedemptionResult) {
        viewModelScope.launch {
            val r = try {
                block()
            } catch (e: Exception) {
                Timber.e(e, "redemption failed")
                feedback.error()
                _messages.emit("驗票失敗：${e.message}")
                return@launch
            }
            val decision = r.outcome.decision
            val text = DecisionText.describe(decision, settings.value.zone)
            val accepted = decision is Decision.Accepted
            _result.value = ScanResultUi(
                id = r.record.id,
                accepted = accepted,
                direction = r.record.direction,
                title = text.title,
                detail = text.detail,
                code = r.outcome.code,
                enrolled = r.outcome.enrolled,
            )
            if (accepted) feedback.success() else feedback.error()
            if (decision is Decision.Accepted) {
                (ticketPrinter.admission(decision.ticket, decision.direction) as? PrinterResult.Error)?.let {
                    _messages.emit("憑證未列印：${it.message}")
                }
            }
        }
    }
}

@Composable
fun RedeemScreen(
    operator: Operator,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: RedeemViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val direction by viewModel.direction.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val printerState by viewModel.printerState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hardware = LocalHardware.current
    val scope = rememberCoroutineScope()
    val openCamera = rememberCameraScanner("掃描票券")
    var menu by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    ScanEffect(viewModel::onScan)
    NfcEffect(viewModel::onNfc)

    val current = result
    LaunchedEffect(current?.id, settings.resultSeconds) {
        if (current != null && settings.resultSeconds > 0) {
            delay(settings.resultSeconds * 1000L)
            viewModel.dismiss(current.id)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar("${settings.gate.gateName} · ${operator.name}") {
                IconButton(onClick = { onNavigate(Routes.DEVICE) }) {
                    val tint = when (printerState) {
                        is PrinterState.Connected -> StatusColors.success
                        PrinterState.Simulated, PrinterState.Connecting -> StatusColors.warning
                        else -> StatusColors.error
                    }
                    Icon(Icons.Filled.Print, contentDescription = "印表機狀態", tint = tint)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "選單") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val go: (() -> Unit) -> () -> Unit = { action ->
                            {
                                menu = false
                                action()
                            }
                        }
                        MenuItem("票券管理", Icons.Filled.ConfirmationNumber, go { onNavigate(Routes.TICKETS) })
                        if (operator.isAdmin) MenuItem("發行票券", Icons.Filled.PostAdd, go { onNavigate(Routes.ISSUE) })
                        MenuItem("驗票紀錄與統計", Icons.Filled.History, go { onNavigate(Routes.LOGS) })
                        MenuItem("設定", Icons.Filled.Settings, go { onNavigate(Routes.SETTINGS) })
                        HorizontalDivider()
                        MenuItem("登出", Icons.Filled.PowerSettingsNew, go(onLogout))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Direction.entries.forEach { d ->
                    FilterChip(
                        selected = direction == d,
                        onClick = { viewModel.setDirection(d) },
                        label = {
                            Text(
                                d.label,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("今日通過", stats.accepted, StatusColors.success, Modifier.weight(1f))
                StatBox("拒絕", stats.rejected, StatusColors.error, Modifier.weight(1f))
                StatBox("場內人數", stats.insideNow, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(targetState = current, label = "result") { r ->
                    if (r == null) {
                        IdlePanel(direction, settings.gate.zone)
                    } else {
                        ResultPanel(r, onClick = { viewModel.dismiss(r.id) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { hardware.scanner.startScanHead() } }, modifier = Modifier.weight(1.4f).height(60.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Text(" 掃描", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = openCamera, modifier = Modifier.weight(1f).height(60.dp)) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "相機")
                }
                OutlinedButton(onClick = { manual = true }, modifier = Modifier.weight(1f).height(60.dp)) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "手動輸入")
                }
            }
        }
    }

    if (manual) {
        TextInputDialog(
            title = "手動輸入票號",
            label = "票券代碼",
            confirmText = "驗票",
            validate = { if (it.isBlank()) "請輸入票號" else null },
            onConfirm = {
                manual = false
                viewModel.manual(it)
            },
            onDismiss = { manual = false },
        )
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

@Composable
private fun StatBox(label: String, value: Int, color: Color, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun IdlePanel(direction: Direction, zone: String?) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            Text("${direction.label}驗票", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Text(
                "請掃描票券 QR 碼，或將卡片靠近感應區",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!zone.isNullOrBlank()) Text("本閘口區域：$zone", modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ResultPanel(result: ScanResultUi, onClick: () -> Unit) {
    val color = if (result.accepted) StatusColors.success else StatusColors.error
    Surface(Modifier.fillMaxSize().clickable(onClick = onClick), color = color, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                if (result.accepted) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                Modifier.size(120.dp),
                tint = Color.White,
            )
            Text(
                result.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            result.detail?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            Text(result.code.take(32), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp))
            if (result.enrolled) Text("（簽章票券，首次於本機驗證）", style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
    }
}
