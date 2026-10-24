package me.longtai.ticket.ui.logs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import me.longtai.core.common.time.TimeFormats
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.EmptyState
import me.longtai.core.ui.components.InfoRow
import me.longtai.core.ui.files.CsvFiles
import me.longtai.core.ui.theme.StatusColors
import me.longtai.ticket.data.LogFilter
import me.longtai.ticket.data.TicketPrinter
import me.longtai.ticket.data.TicketRepository
import me.longtai.ticket.data.TicketSettings
import me.longtai.ticket.data.TicketSettingsRepository
import me.longtai.ticket.domain.model.RedemptionRecord
import me.longtai.ticket.domain.print.GateStats
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogsViewModel @Inject constructor(
    private val repository: TicketRepository,
    private val printer: TicketPrinter,
    private val files: CsvFiles,
    settingsRepository: TicketSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<TicketSettings> = settingsRepository.settings
    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()
    private val _filter = MutableStateFlow(LogFilter.ALL)
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    val records: StateFlow<List<RedemptionRecord>> = combine(_date, _filter) { d, f -> d to f }
        .flatMapLatest { (d, f) -> repository.logs(d, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<GateStats> = _date.flatMapLatest { repository.stats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateStats(0, 0, 0, 0, 0))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun shiftDate(days: Long) {
        val next = _date.value.plusDays(days)
        if (!next.isAfter(LocalDate.now())) _date.value = next
    }

    fun setFilter(f: LogFilter) {
        _filter.value = f
    }

    fun printSummary() {
        viewModelScope.launch {
            val r = printer.dailySummary(_date.value, repository.statsOnce(_date.value))
            _messages.emit(if (r is PrinterResult.Error) r.message else "已送出列印")
        }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            runCatching { files.write(uri, repository.exportLogs(_date.value)) }
                .onSuccess { _messages.emit("已匯出驗票紀錄") }
                .onFailure { _messages.emit("匯出失敗：${it.message}") }
        }
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit, viewModel: LogsViewModel = hiltViewModel()) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val date by viewModel.date.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            AppTopBar("驗票紀錄", onBack = onBack) {
                IconButton(onClick = viewModel::printSummary) { Icon(Icons.Filled.Print, contentDescription = "列印日報") }
                IconButton(onClick = { exportLauncher.launch("gate-log-$date.csv") }) { Icon(Icons.Filled.FileDownload, contentDescription = "匯出") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.shiftDate(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天") }
                Text(TimeFormats.date(date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.shiftDate(1) }, enabled = date.isBefore(LocalDate.now())) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "後一天")
                }
            }
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    InfoRow("通過 / 拒絕", "${stats.accepted} / ${stats.rejected}")
                    InfoRow("入場 / 出場人次", "${stats.entries} / ${stats.exits}")
                    InfoRow("目前場內", "${stats.insideNow}", emphasize = true)
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) }) }
            }
            if (records.isEmpty()) {
                EmptyState(Icons.Filled.History, "沒有紀錄")
            } else {
                LazyColumn {
                    items(records, key = { it.id }) { r ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    (if (r.accepted) "✔ " else "✘ ") + r.direction.label + " · " + (r.holderName ?: r.code.take(20)),
                                    color = if (r.accepted) StatusColors.success else StatusColors.error,
                                )
                            },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        TimeFormats.time(r.at, settings.zone),
                                        r.reason?.label,
                                        r.ticketType?.label,
                                        r.operatorName,
                                    ).joinToString(" · "),
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
