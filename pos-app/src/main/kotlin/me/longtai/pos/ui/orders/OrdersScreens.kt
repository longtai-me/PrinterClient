package me.longtai.pos.ui.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.core.auth.Operator
import me.longtai.core.auth.ui.AdminAuthorization
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.common.time.TimeFormats
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.EmptyState
import me.longtai.core.ui.components.InfoRow
import me.longtai.core.ui.components.TextInputDialog
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.core.ui.theme.StatusColors
import me.longtai.pos.data.PosPrinter
import me.longtai.pos.data.PosSettings
import me.longtai.pos.data.PosSettingsRepository
import me.longtai.pos.data.SalesRepository
import me.longtai.pos.data.ValidationException
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.OrderStatus
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val sales: SalesRepository,
    settingsRepository: PosSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<PosSettings> = settingsRepository.settings
    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()
    val orders: StateFlow<List<Order>> = _date.flatMapLatest { sales.ordersOn(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun shiftDate(days: Long) {
        val next = _date.value.plusDays(days)
        if (!next.isAfter(LocalDate.now())) _date.value = next
    }

    /** Scanning the barcode printed on a receipt opens that order. */
    suspend fun findByScan(event: ScanEvent): Long? {
        val order = sales.findOrderByNo(event.code)
        if (order == null) _messages.emit("找不到交易 ${event.code}")
        return order?.id
    }
}

@Composable
fun OrdersScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: OrdersViewModel = hiltViewModel()) {
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val date by viewModel.date.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    ScanEffect { event -> scope.launch { viewModel.findByScan(event)?.let(onOpen) } }

    Scaffold(topBar = { AppTopBar("交易紀錄", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.shiftDate(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天") }
                Text(TimeFormats.date(date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.shiftDate(1) }, enabled = date.isBefore(LocalDate.now())) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "後一天")
                }
            }
            val completed = orders.filter { it.status == OrderStatus.COMPLETED }
            Text(
                "共 ${orders.size} 筆 · 有效銷售 ${settings.money.format(me.longtai.core.common.money.Money.sum(completed.map { it.total }))}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text("提示：掃描收據上的條碼可直接開啟該筆交易", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            if (orders.isEmpty()) {
                EmptyState(Icons.Filled.Receipt, "這一天沒有交易")
            } else {
                LazyColumn {
                    items(orders, key = { it.id }) { o ->
                        ListItem(
                            headlineContent = { Text(o.orderNo) },
                            supportingContent = {
                                Text("${TimeFormats.time(o.createdAt, settings.zone)} · ${o.operatorName} · ${o.itemCount} 件" + (o.memberName?.let { " · $it" } ?: ""))
                            },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(settings.money.format(o.total), style = MaterialTheme.typography.titleMedium)
                                    if (o.status == OrderStatus.REFUNDED) Text("已退貨", color = StatusColors.error, style = MaterialTheme.typography.labelMedium)
                                }
                            },
                            modifier = Modifier.clickable { onOpen(o.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val sales: SalesRepository,
    private val printer: PosPrinter,
    settingsRepository: PosSettingsRepository,
) : ViewModel() {
    private val orderId: Long = savedState.get<Long>("id") ?: 0L
    val settings: StateFlow<PosSettings> = settingsRepository.settings
    val order: StateFlow<Order?> = sales.observeOrder(orderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun reprint() {
        val o = order.value ?: return
        viewModelScope.launch {
            val r = if (o.status == OrderStatus.REFUNDED) printer.refundSlip(o) else printer.receipt(o, reprint = true)
            _messages.emit(if (r is PrinterResult.Error) r.message else "已送出列印")
        }
    }

    fun refund(authorizedBy: Operator, reason: String) {
        viewModelScope.launch {
            try {
                val shift = sales.currentShift() ?: throw ValidationException("請先開班再辦理退貨")
                val refunded = sales.refund(orderId, shift.id, authorizedBy.name, reason)
                _messages.emit("退貨完成，請退還 ${settings.value.money.format(refunded.total)}")
                (printer.openDrawerIfEnabled() as? PrinterResult.Error)?.let { _messages.emit("錢箱：${it.message}") }
                (printer.refundSlip(refunded) as? PrinterResult.Error)?.let { _messages.emit("退貨單未列印：${it.message}") }
            } catch (e: ValidationException) {
                _messages.emit(e.message ?: "退貨失敗")
            }
        }
    }
}

@Composable
fun OrderDetailScreen(onBack: () -> Unit, viewModel: OrderDetailViewModel = hiltViewModel()) {
    val order by viewModel.order.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var askReason by remember { mutableStateOf(false) }
    var pendingReason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(topBar = { AppTopBar("交易明細", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val o = order
        if (o == null) {
            EmptyState(Icons.Filled.Receipt, "載入中…", modifier = Modifier.padding(padding))
        } else {
            val money = settings.money
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.orderNo, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    AssistChip(onClick = {}, label = { Text(o.status.label) })
                }
                Text("${TimeFormats.dateTime(o.createdAt, settings.zone)} · 收銀員 ${o.operatorName}")
                o.memberNo?.let { Text("會員 $it ${o.memberName.orEmpty()} · 累積 ${o.pointsEarned} 點") }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        o.lines.forEach { line ->
                            InfoRow("${line.name} × ${line.quantity}", money.format(line.net))
                            if (line.discount.isPositive) {
                                Text("  原價 ${money.format(line.unitPrice * line.quantity)}，折 ${money.format(line.discount)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        if (o.memberDiscount.isPositive) InfoRow("會員折扣", "-${money.format(o.memberDiscount)}")
                        if (o.orderDiscount.isPositive) InfoRow("整單折扣", "-${money.format(o.orderDiscount)}")
                        if (o.tax.isPositive) InfoRow("稅額", money.format(o.tax))
                        InfoRow("合計", money.format(o.total), emphasize = true)
                        o.payments.forEach { p -> InfoRow(p.method.label, money.format(p.tendered)) }
                        if (o.change.isPositive) InfoRow("找零", money.format(o.change))
                    }
                }
                if (o.status == OrderStatus.REFUNDED) {
                    Text(
                        "已於 ${o.refundedAt?.let { TimeFormats.dateTime(it, settings.zone) }} 退貨（經辦：${o.refundOperatorName.orEmpty()}）" +
                            (o.refundReason?.let { "，原因：$it" } ?: ""),
                        color = StatusColors.error,
                    )
                }
                OutlinedButton(onClick = viewModel::reprint, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (o.status == OrderStatus.REFUNDED) "列印退貨單" else "補印收據")
                }
                if (o.status == OrderStatus.COMPLETED) {
                    Button(
                        onClick = { askReason = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("整筆退貨") }
                }
            }
        }
    }

    if (askReason) {
        TextInputDialog(
            title = "退貨原因",
            label = "例：商品瑕疵、顧客反悔",
            confirmText = "下一步",
            validate = { if (it.isBlank()) "請輸入退貨原因" else null },
            onConfirm = {
                askReason = false
                pendingReason = it
            },
            onDismiss = { askReason = false },
        )
    }
    pendingReason?.let { reason ->
        AdminAuthorization(
            reason = "退貨需管理員授權",
            onAuthorized = { admin ->
                pendingReason = null
                viewModel.refund(admin, reason)
            },
            onDismiss = { pendingReason = null },
        )
    }
}
