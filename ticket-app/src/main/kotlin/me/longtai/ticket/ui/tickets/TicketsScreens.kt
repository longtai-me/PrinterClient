package me.longtai.ticket.ui.tickets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.core.auth.Session
import me.longtai.core.auth.ui.AdminAuthorization
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.common.time.TimeFormats
import me.longtai.core.hardware.feedback.Feedback
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.EmptyState
import me.longtai.core.ui.components.InfoRow
import me.longtai.core.ui.files.CsvFiles
import me.longtai.core.ui.hardware.NfcEffect
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.core.ui.theme.StatusColors
import me.longtai.ticket.data.TicketException
import me.longtai.ticket.data.TicketFilter
import me.longtai.ticket.data.TicketPrinter
import me.longtai.ticket.data.TicketRepository
import me.longtai.ticket.data.TicketSettings
import me.longtai.ticket.data.TicketSettingsRepository
import me.longtai.ticket.domain.codec.SignedDecode
import me.longtai.ticket.domain.model.RedemptionRecord
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketStatus
import java.time.LocalDate
import javax.inject.Inject

fun Ticket.statusText(): String = when {
    status == TicketStatus.VOID -> "已作廢"
    remainingUses == 0 -> "已用完"
    inside -> "場內"
    else -> "有效"
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TicketsViewModel @Inject constructor(
    private val repository: TicketRepository,
    private val files: CsvFiles,
    settingsRepository: TicketSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<TicketSettings> = settingsRepository.settings
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _filter = MutableStateFlow(TicketFilter.ALL)
    val filter: StateFlow<TicketFilter> = _filter.asStateFlow()
    val tickets: StateFlow<List<Ticket>> = combine(_query, _filter) { q, f -> q to f }
        .flatMapLatest { (q, f) -> repository.search(q, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _report = MutableStateFlow<TicketRepository.ImportSummary?>(null)
    val report: StateFlow<TicketRepository.ImportSummary?> = _report.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setFilter(f: TicketFilter) {
        _filter.value = f
    }

    /** Scanning a ticket searches for it; signed codes are decoded to their ticket code. */
    fun onScan(event: ScanEvent) {
        val decoded = settings.value.codec()?.decode(event.code)
        _query.value = if (decoded is SignedDecode.Valid) decoded.claims.code else event.code
    }

    fun onNfc(tag: NfcTag) {
        _query.value = tag.uid
    }

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching { files.write(uri, repository.exportCsv()) }
            .onSuccess { _messages.emit("已匯出票券資料") }
            .onFailure { _messages.emit("匯出失敗：${it.message}") }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        runCatching { repository.importCsv(files.read(uri)) }
            .onSuccess { _report.value = it }
            .onFailure { _messages.emit("匯入失敗：${it.message}") }
    }

    fun dismissReport() {
        _report.value = null
    }
}

@Composable
fun TicketsScreen(
    isAdmin: Boolean,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onIssue: () -> Unit,
    viewModel: TicketsViewModel = hiltViewModel(),
) {
    val tickets by viewModel.tickets.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    ScanEffect(viewModel::onScan)
    NfcEffect(viewModel::onNfc)

    Scaffold(
        topBar = {
            AppTopBar("票券管理", onBack = onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (isAdmin) {
                            DropdownMenuItem(text = { Text("匯入 CSV") }, onClick = {
                                menu = false
                                importLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream"))
                            })
                        }
                        DropdownMenuItem(text = { Text("匯出 CSV") }, onClick = {
                            menu = false
                            exportLauncher.launch("tickets-${LocalDate.now()}.csv")
                        })
                    }
                }
            }
        },
        floatingActionButton = {
            if (isAdmin) {
                ExtendedFloatingActionButton(onClick = onIssue, icon = { Icon(Icons.Filled.PostAdd, contentDescription = null) }, text = { Text("發行票券") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("票號 / 持票人，或掃描票券、感應卡片") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TicketFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) }) }
            }
            if (tickets.isEmpty()) {
                EmptyState(Icons.Filled.ConfirmationNumber, "沒有票券", if (isAdmin) "可發行新票券或從選單匯入 CSV" else null)
            } else {
                LazyColumn {
                    items(tickets, key = { it.id }) { t ->
                        ListItem(
                            headlineContent = { Text(t.holderName?.let { "$it · ${t.code}" } ?: t.code) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        t.type.label,
                                        t.remainingUses?.let { "剩 $it 次" },
                                        t.zone?.let { "區域 $it" },
                                        t.validUntil?.let { "至 ${TimeFormats.dateTimeShort(it, settings.zone)}" },
                                        if (t.nfcUid != null) "已綁卡" else null,
                                    ).joinToString(" · "),
                                )
                            },
                            trailingContent = {
                                val text = t.statusText()
                                Text(text, color = if (text == "有效" || text == "場內") StatusColors.success else StatusColors.error)
                            },
                            modifier = Modifier.clickable { onOpen(t.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    report?.let { r ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReport,
            title = { Text("匯入結果") },
            text = {
                Column {
                    Text("新增 ${r.inserted} 張、更新 ${r.updated} 張" + if (r.errors.isNotEmpty()) "，${r.errors.size} 筆有誤：" else "")
                    r.errors.take(20).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissReport) { Text("確定") } },
        )
    }
}

@HiltViewModel
class TicketDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: TicketRepository,
    private val printer: TicketPrinter,
    private val feedback: Feedback,
    private val session: Session,
    settingsRepository: TicketSettingsRepository,
) : ViewModel() {
    private val ticketId: Long = savedState.get<Long>("id") ?: 0L
    val settings: StateFlow<TicketSettings> = settingsRepository.settings
    val ticket: StateFlow<Ticket?> = repository.observe(ticketId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<List<RedemptionRecord>> = repository.history(ticketId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _binding = MutableStateFlow(false)
    val binding: StateFlow<Boolean> = _binding.asStateFlow()

    val isAdmin: Boolean get() = session.current.value?.isAdmin == true

    fun startBinding() {
        _binding.value = true
    }

    fun cancelBinding() {
        _binding.value = false
    }

    fun onNfc(tag: NfcTag) {
        if (!_binding.value) return
        act("已綁定卡片 ${tag.uid}") {
            repository.bindCard(ticketId, tag.uid)
            feedback.success()
            _binding.value = false
        }
    }

    fun unbindCard() = act("已解除卡片綁定") { repository.bindCard(ticketId, null) }

    fun setVoid(void: Boolean) = act(if (void) "票券已作廢" else "票券已恢復") {
        repository.setStatus(ticketId, if (void) TicketStatus.VOID else TicketStatus.ACTIVE)
    }

    fun resetUsage() = act("使用次數已歸零") { repository.resetUsage(ticketId) }

    fun reprint() {
        val t = ticket.value ?: return
        viewModelScope.launch {
            val r = printer.ticket(t)
            _messages.emit(if (r is PrinterResult.Error) r.message else "已送出列印")
        }
    }

    private fun act(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _messages.emit(success)
            } catch (e: TicketException) {
                feedback.error()
                _messages.emit(e.message ?: "操作失敗")
            }
        }
    }
}

private enum class PendingAdminAction { VOID, RESTORE, RESET }

@Composable
fun TicketDetailScreen(onBack: () -> Unit, viewModel: TicketDetailViewModel = hiltViewModel()) {
    val ticket by viewModel.ticket.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val binding by viewModel.binding.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<PendingAdminAction?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    NfcEffect(viewModel::onNfc)

    Scaffold(topBar = { AppTopBar("票券明細", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val t = ticket
        if (t == null) {
            EmptyState(Icons.Filled.ConfirmationNumber, "載入中…", modifier = Modifier.padding(padding))
        } else {
            TicketDetailContent(t, history, settings, binding, Modifier.padding(padding), viewModel) { pending = it }
        }
    }

    pending?.let { action ->
        AdminAuthorization(
            reason = when (action) {
                PendingAdminAction.VOID -> "作廢票券需管理員授權"
                PendingAdminAction.RESTORE -> "恢復票券需管理員授權"
                PendingAdminAction.RESET -> "重設使用次數需管理員授權"
            },
            onAuthorized = {
                pending = null
                when (action) {
                    PendingAdminAction.VOID -> viewModel.setVoid(true)
                    PendingAdminAction.RESTORE -> viewModel.setVoid(false)
                    PendingAdminAction.RESET -> viewModel.resetUsage()
                }
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun TicketDetailContent(
    t: Ticket,
    history: List<RedemptionRecord>,
    settings: TicketSettings,
    binding: Boolean,
    modifier: Modifier,
    viewModel: TicketDetailViewModel,
    onAdminAction: (PendingAdminAction) -> Unit,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row {
                Text(t.code, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(t.statusText()) })
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow("票種", t.type.label)
                    InfoRow("持票人", t.holderName ?: "-")
                    InfoRow("區域", t.zone ?: "不限")
                    InfoRow("開始", t.validFrom?.let { TimeFormats.dateTimeShort(it, settings.zone) } ?: "不限")
                    InfoRow("結束", t.validUntil?.let { TimeFormats.dateTimeShort(it, settings.zone) } ?: "不限")
                    InfoRow("已使用", "${t.usedCount}" + (t.maxUses?.let { " / $it 次" } ?: " 次（不限）"))
                    InfoRow("NFC 卡", t.nfcUid ?: "未綁定")
                    t.lastEntryAt?.let { InfoRow("最後入場", TimeFormats.dateTimeShort(it, settings.zone)) }
                    t.lastExitAt?.let { InfoRow("最後出場", TimeFormats.dateTimeShort(it, settings.zone)) }
                    t.note?.let { InfoRow("備註", it) }
                }
            }
        }
        item {
            OutlinedButton(onClick = viewModel::reprint, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("列印票券 QR") }
        }
        item {
            if (binding) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp)) {
                        Icon(Icons.Filled.Nfc, contentDescription = null)
                        Text("  請將卡片靠近感應區…", modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::cancelBinding) { Text("取消") }
                    }
                }
            } else if (t.nfcUid == null) {
                OutlinedButton(onClick = viewModel::startBinding, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("綁定 NFC 卡片") }
            } else {
                OutlinedButton(onClick = viewModel::unbindCard, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("解除 NFC 卡片") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.status == TicketStatus.VOID) {
                    OutlinedButton(onClick = { onAdminAction(PendingAdminAction.RESTORE) }, modifier = Modifier.weight(1f)) { Text("恢復票券") }
                } else {
                    Button(onClick = { onAdminAction(PendingAdminAction.VOID) }, modifier = Modifier.weight(1f)) { Text("作廢票券") }
                }
                OutlinedButton(onClick = { onAdminAction(PendingAdminAction.RESET) }, modifier = Modifier.weight(1f)) { Text("使用次數歸零") }
            }
        }
        item { Text("驗票紀錄", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (history.isEmpty()) item { Text("尚無紀錄", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(history, key = { it.id }) { r ->
            ListItem(
                headlineContent = { Text("${r.direction.label} · " + if (r.accepted) "通過" else (r.reason?.label ?: "拒絕")) },
                supportingContent = { Text("${TimeFormats.dateTime(r.at, settings.zone)} · ${r.gateName} · ${r.operatorName}") },
            )
        }
    }
}
