package me.longtai.pos.ui.shift

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.core.auth.Session
import me.longtai.core.common.money.Money
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.common.time.TimeFormats
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.InfoRow
import me.longtai.core.ui.components.KeypadDisplay
import me.longtai.core.ui.components.NumericKeypad
import me.longtai.core.ui.components.SectionHeader
import me.longtai.core.ui.components.appendAmountKey
import me.longtai.core.ui.theme.StatusColors
import me.longtai.pos.data.PosPrinter
import me.longtai.pos.data.PosSettings
import me.longtai.pos.data.PosSettingsRepository
import me.longtai.pos.data.SalesRepository
import me.longtai.pos.data.ValidationException
import me.longtai.pos.domain.model.PaymentMethod
import me.longtai.pos.domain.model.Shift
import me.longtai.pos.domain.report.CashReconciliation
import me.longtai.pos.domain.report.SalesReport
import me.longtai.pos.domain.report.SalesSummary
import java.time.LocalDate
import javax.inject.Inject

data class ShiftReport(val shift: Shift, val summary: SalesSummary, val cash: CashReconciliation)

@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val sales: SalesRepository,
    private val printer: PosPrinter,
    private val session: Session,
    settingsRepository: PosSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<PosSettings> = settingsRepository.settings
    val recentShifts: StateFlow<List<Shift>> = sales.recentShifts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _current = MutableStateFlow<ShiftReport?>(null)
    val current: StateFlow<ShiftReport?> = _current.asStateFlow()

    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()
    private val _daily = MutableStateFlow<SalesSummary?>(null)
    val daily: StateFlow<SalesSummary?> = _daily.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    fun refresh() {
        viewModelScope.launch {
            _current.value = sales.currentShift()?.let { report(it) }
            loadDaily()
        }
    }

    private suspend fun report(shift: Shift, counted: Money? = shift.countedCash): ShiftReport {
        val (s, r) = sales.salesAndRefundsInShift(shift.id)
        val summary = SalesReport.summarize(s, r)
        return ShiftReport(shift, summary, SalesReport.reconcile(shift, summary, counted))
    }

    private suspend fun loadDaily() {
        val (s, r) = sales.salesAndRefundsOn(_date.value)
        _daily.value = SalesReport.summarize(s, r, topN = 10)
    }

    fun shiftDate(days: Long) {
        val next = _date.value.plusDays(days)
        if (next.isAfter(LocalDate.now())) return
        _date.value = next
        viewModelScope.launch { loadDaily() }
    }

    fun printX() {
        val r = _current.value ?: return
        viewModelScope.launch { announce(printer.shiftReport(r.shift, r.summary, r.cash, closing = false)) }
    }

    fun printShift(shift: Shift) {
        viewModelScope.launch {
            val r = report(shift)
            announce(printer.shiftReport(r.shift, r.summary, r.cash, closing = shift.closedAt != null))
        }
    }

    fun printDaily() {
        val summary = _daily.value ?: return
        viewModelScope.launch { announce(printer.dailyReport(_date.value, summary)) }
    }

    fun close(counted: Money, note: String) {
        val r = _current.value ?: return
        val operator = session.current.value ?: return
        viewModelScope.launch {
            try {
                val closedShift = sales.closeShift(r.shift.id, operator.name, counted, note)
                val finalReport = report(closedShift, counted)
                announce(printer.shiftReport(finalReport.shift, finalReport.summary, finalReport.cash, closing = true))
                _closed.emit(Unit)
            } catch (e: ValidationException) {
                _messages.emit(e.message ?: "交班失敗")
            }
        }
    }

    private suspend fun announce(result: PrinterResult<Unit>) {
        _messages.emit(if (result is PrinterResult.Error) "未列印：${result.message}" else "報表列印中")
    }
}

@Composable
fun ShiftScreen(onBack: () -> Unit, onShiftClosed: () -> Unit, viewModel: ShiftViewModel = hiltViewModel()) {
    val current by viewModel.current.collectAsStateWithLifecycle()
    val daily by viewModel.daily.collectAsStateWithLifecycle()
    val date by viewModel.date.collectAsStateWithLifecycle()
    val shifts by viewModel.recentShifts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val money = settings.money
    val snackbar = remember { SnackbarHostState() }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.closed.collect { onShiftClosed() } }

    Scaffold(topBar = { AppTopBar("班別與報表", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionHeader("目前班別") }
            item {
                val r = current
                if (r == null) {
                    Text("目前沒有開啟中的班別", Modifier.padding(16.dp))
                } else {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            InfoRow("收銀員", r.shift.operatorName)
                            InfoRow("開班時間", TimeFormats.dateTimeShort(r.shift.openedAt, settings.zone))
                            SummaryRows(r.summary, money)
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            InfoRow("開班零用金", money.format(r.cash.openingCash))
                            InfoRow("現金收支(淨)", money.format(r.cash.cashIn))
                            InfoRow("應有現金", money.format(r.cash.expectedCash), emphasize = true)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::printX, modifier = Modifier.weight(1f).height(52.dp)) { Text("列印 X 報表") }
                        Button(onClick = { closing = true }, modifier = Modifier.weight(1f).height(52.dp)) { Text("交班結帳") }
                    }
                }
            }

            item { SectionHeader("營業日報") }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.shiftDate(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天") }
                    Text(TimeFormats.date(date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.shiftDate(1) }, enabled = date.isBefore(LocalDate.now())) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "後一天")
                    }
                }
            }
            item {
                daily?.let { d ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            SummaryRows(d, money)
                            if (d.topProducts.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                Text("熱銷商品", style = MaterialTheme.typography.titleSmall)
                                d.topProducts.forEachIndexed { i, p -> InfoRow("${i + 1}. ${p.name} ×${p.quantity}", money.format(p.amount)) }
                            }
                        }
                    }
                    OutlinedButton(onClick = viewModel::printDaily, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text("列印日報表") }
                }
            }

            item { SectionHeader("班別歷史（點選可補印報表）") }
            items(shifts, key = { it.id }) { s ->
                ListItem(
                    headlineContent = { Text("#${s.id} ${s.operatorName}") },
                    supportingContent = {
                        Text(
                            TimeFormats.dateTimeShort(s.openedAt, settings.zone) + " ~ " +
                                (s.closedAt?.let { TimeFormats.dateTimeShort(it, settings.zone) } ?: "進行中"),
                        )
                    },
                    trailingContent = { s.countedCash?.let { Text("實點 ${money.format(it)}") } },
                    modifier = Modifier.clickable { viewModel.printShift(s) },
                )
            }
        }
    }

    val r = current
    if (closing && r != null) {
        CloseShiftDialog(
            expected = r.cash.expectedCash,
            money = money,
            onConfirm = { counted, note ->
                closing = false
                viewModel.close(counted, note)
            },
            onDismiss = { closing = false },
        )
    }
}

@Composable
private fun SummaryRows(summary: SalesSummary, money: MoneyFormat) {
    InfoRow("交易筆數", "${summary.orderCount}")
    InfoRow("退貨筆數", "${summary.refundCount}")
    InfoRow("銷售總額", money.format(summary.grossSales))
    InfoRow("退貨金額", "-${money.format(summary.refunds)}")
    InfoRow("淨銷售額", money.format(summary.netSales), emphasize = true)
    InfoRow("折扣合計", money.format(summary.discounts))
    PaymentMethod.entries.forEach { m -> summary.byMethod[m]?.let { InfoRow("  ${m.label}", money.format(it)) } }
}

@Composable
private fun CloseShiftDialog(expected: Money, money: MoneyFormat, onConfirm: (Money, String) -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val counted = money.parse(amount.ifEmpty { "0" }) ?: Money.ZERO
    val variance = counted - expected
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("交班結帳") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("應有現金 ${money.format(expected)}，請清點錢櫃後輸入實際金額")
                KeypadDisplay(amount, placeholder = money.format(Money.ZERO))
                Text(
                    when {
                        amount.isEmpty() -> " "
                        variance.isZero -> "現金相符"
                        variance.isNegative -> "短少 ${money.format(-variance)}"
                        else -> "溢收 ${money.format(variance)}"
                    },
                    color = if (variance.isZero) StatusColors.success else StatusColors.error,
                )
                NumericKeypad(
                    onKey = { amount = appendAmountKey(amount, it, money.decimals) },
                    onBackspace = { amount = amount.dropLast(1) },
                    extraKey = if (money.decimals > 0) "." else "",
                    onExtraKey = { amount = appendAmountKey(amount, '.', money.decimals) },
                    keyHeight = 44,
                )
                OutlinedTextField(note, { note = it.take(100) }, label = { Text("備註（差額說明）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(counted, note) }, enabled = amount.isNotEmpty()) { Text("確認交班並列印 Z 報表") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
