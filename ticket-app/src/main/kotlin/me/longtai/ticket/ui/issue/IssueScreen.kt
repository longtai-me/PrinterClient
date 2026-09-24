package me.longtai.ticket.ui.issue

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.longtai.core.common.time.TimeFormats
import me.longtai.core.hardware.feedback.Feedback
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.hardware.NfcEffect
import me.longtai.ticket.data.PrintTarget
import me.longtai.ticket.data.TicketException
import me.longtai.ticket.data.TicketPrinter
import me.longtai.ticket.data.TicketRepository
import me.longtai.ticket.data.TicketSettingsRepository
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class IssueForm(
    val type: TicketType = TicketType.SINGLE,
    val holder: String = "",
    val zone: String = "",
    val from: String = "",
    val until: String = "",
    val maxUses: String = "5",
    val quantity: String = "1",
    val note: String = "",
    val cardUid: String? = null,
    val target: PrintTarget = PrintTarget.RECEIPT,
    val busy: Boolean = false,
    val progress: String? = null,
)

@HiltViewModel
class IssueViewModel @Inject constructor(
    private val repository: TicketRepository,
    private val printer: TicketPrinter,
    private val feedback: Feedback,
    private val settingsRepository: TicketSettingsRepository,
) : ViewModel() {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val _form = MutableStateFlow(IssueForm(target = settingsRepository.settings.value.issueTarget))
    val form: StateFlow<IssueForm> = _form.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _issued = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val issued: SharedFlow<Long> = _issued.asSharedFlow()

    fun update(transform: (IssueForm) -> IssueForm) = _form.update(transform)

    /** Quick validity presets. */
    fun preset(days: Int?) {
        if (days == null) {
            _form.update { it.copy(from = "", until = "") }
            return
        }
        val today = LocalDate.now()
        _form.update {
            it.copy(
                from = today.atStartOfDay().format(format),
                until = today.plusDays((days - 1).toLong()).atTime(LocalTime.of(23, 59)).format(format),
            )
        }
    }

    fun onNfc(tag: NfcTag) {
        feedback.success()
        _form.update { it.copy(cardUid = tag.uid, quantity = "1") }
    }

    fun issue() {
        val f = _form.value
        if (f.busy) return
        val zone = settingsRepository.settings.value.zone
        val from = if (f.from.isBlank()) null else TimeFormats.parseDateTime(f.from, zone)
        val until = if (f.until.isBlank()) null else TimeFormats.parseDateTime(f.until, zone, endOfDay = true)
        val quantity = f.quantity.toIntOrNull()
        val maxUses = f.maxUses.toIntOrNull()
        val error = when {
            f.from.isNotBlank() && from == null -> "開始時間格式錯誤（yyyy-MM-dd HH:mm）"
            f.until.isNotBlank() && until == null -> "結束時間格式錯誤（yyyy-MM-dd HH:mm）"
            quantity == null || quantity !in 1..200 -> "張數需介於 1–200"
            f.type == TicketType.MULTI && (maxUses == null || maxUses < 1) -> "多次票請輸入可用次數"
            else -> null
        }
        if (error != null) {
            _messages.tryEmit(error)
            return
        }
        val template = Ticket(
            code = "",
            nfcUid = f.cardUid,
            type = f.type,
            holderName = f.holder.trim().ifEmpty { null },
            zone = f.zone.trim().ifEmpty { null },
            validFrom = from,
            validUntil = until,
            maxUses = if (f.type == TicketType.MULTI) maxUses else null,
            createdAt = 0,
            note = f.note.trim().ifEmpty { null },
        )
        _form.update { it.copy(busy = true, progress = "建立票券中…") }
        viewModelScope.launch {
            try {
                val tickets = repository.issue(template, quantity!!)
                var failed = 0
                if (f.target != PrintTarget.NONE) {
                    for ((index, t) in tickets.withIndex()) {
                        _form.update { it.copy(progress = "列印中 ${index + 1}/${tickets.size}") }
                        val r = printer.ticket(t, f.target)
                        if (r is PrinterResult.Error) {
                            // Stop at the first failure; the rest would fail the same way.
                            failed = tickets.size - index
                            _messages.emit("列印失敗：${r.message}")
                            break
                        }
                    }
                }
                feedback.success()
                _messages.emit("已發行 ${tickets.size} 張票券" + if (failed > 0) "（$failed 張未印出，可於票券明細補印）" else "")
                _form.update { IssueForm(type = it.type, zone = it.zone, from = it.from, until = it.until, maxUses = it.maxUses, target = it.target) }
                if (tickets.size == 1) _issued.emit(tickets.first().id)
            } catch (e: TicketException) {
                _messages.emit(e.message ?: "發行失敗")
                _form.update { it.copy(busy = false, progress = null) }
            }
        }
    }
}

@Composable
fun IssueScreen(onBack: () -> Unit, onIssued: (Long) -> Unit, viewModel: IssueViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.issued.collect { onIssued(it) } }
    NfcEffect(viewModel::onNfc)

    Scaffold(topBar = { AppTopBar("發行票券", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (form.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                form.progress?.let { Text(it) }
            }
            Text("票種", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TicketType.entries.forEach { t -> FilterChip(selected = form.type == t, onClick = { viewModel.update { it.copy(type = t) } }, label = { Text(t.label) }) }
            }
            if (form.type == TicketType.MULTI) {
                OutlinedTextField(
                    form.maxUses, { v -> viewModel.update { it.copy(maxUses = v.filter(Char::isDigit).take(4)) } }, Modifier.fillMaxWidth(),
                    label = { Text("可使用次數") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(form.holder, { v -> viewModel.update { it.copy(holder = v.take(30)) } }, Modifier.fillMaxWidth(), label = { Text("持票人（選填）") }, singleLine = true)
            OutlinedTextField(form.zone, { v -> viewModel.update { it.copy(zone = v.take(30)) } }, Modifier.fillMaxWidth(), label = { Text("可入區域（逗號分隔，空白=不限）") }, singleLine = true)

            Text("有效期間", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { viewModel.preset(1) }, label = { Text("今日") })
                AssistChip(onClick = { viewModel.preset(3) }, label = { Text("3 天") })
                AssistChip(onClick = { viewModel.preset(30) }, label = { Text("30 天") })
                AssistChip(onClick = { viewModel.preset(365) }, label = { Text("一年") })
                AssistChip(onClick = { viewModel.preset(null) }, label = { Text("不限") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.from, { v -> viewModel.update { it.copy(from = v.take(16)) } }, Modifier.weight(1f), label = { Text("開始") }, placeholder = { Text("yyyy-MM-dd HH:mm") }, singleLine = true)
                OutlinedTextField(form.until, { v -> viewModel.update { it.copy(until = v.take(16)) } }, Modifier.weight(1f), label = { Text("結束") }, placeholder = { Text("yyyy-MM-dd HH:mm") }, singleLine = true)
            }

            OutlinedTextField(
                form.quantity, { v -> viewModel.update { it.copy(quantity = v.filter(Char::isDigit).take(3)) } }, Modifier.fillMaxWidth(),
                label = { Text("張數（1–200）") }, singleLine = true, enabled = form.cardUid == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            ListItem(
                headlineContent = { Text(form.cardUid?.let { "綁定卡片 $it" } ?: "綁定 NFC 卡片（選填）") },
                supportingContent = { Text("將卡片靠近感應區即可綁定，持卡即可入場（僅限單張發行）") },
                leadingContent = { Icon(Icons.Filled.Nfc, contentDescription = null) },
                trailingContent = {
                    if (form.cardUid != null) TextButton(onClick = { viewModel.update { it.copy(cardUid = null) } }) { Text("移除") }
                },
            )
            OutlinedTextField(form.note, { v -> viewModel.update { it.copy(note = v.take(60)) } }, Modifier.fillMaxWidth(), label = { Text("備註（選填）") }, singleLine = true)

            Text("列印", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrintTarget.entries.forEach { t -> FilterChip(selected = form.target == t, onClick = { viewModel.update { it.copy(target = t) } }, label = { Text(t.label) }) }
            }
            Button(onClick = viewModel::issue, enabled = !form.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("發行 ${form.quantity.ifEmpty { "0" }} 張${form.type.label}")
            }
        }
    }
}
