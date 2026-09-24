package me.longtai.pos.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
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
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.ClickRow
import me.longtai.core.ui.components.SectionHeader
import me.longtai.core.ui.components.SwitchRow
import me.longtai.core.ui.files.CsvFiles
import me.longtai.pos.data.PosSettings
import me.longtai.pos.data.PosSettingsRepository
import me.longtai.pos.data.SalesRepository
import me.longtai.pos.domain.checkout.OrderNumbers
import me.longtai.pos.domain.io.OrderCsv
import me.longtai.pos.domain.model.StoreProfile
import me.longtai.pos.domain.model.TaxConfig
import me.longtai.pos.domain.model.TaxMode
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: PosSettingsRepository,
    private val sales: SalesRepository,
    private val files: CsvFiles,
) : ViewModel() {
    val settings: StateFlow<PosSettings> = repository.settings
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun update(message: String = "已儲存", transform: (PosSettings) -> PosSettings) {
        viewModelScope.launch {
            repository.update(transform)
            _messages.emit(message)
        }
    }

    /** Exports this month's orders (one row per line item) for bookkeeping. */
    fun exportOrders(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val today = LocalDate.now()
                val orders = sales.ordersBetween(today.withDayOfMonth(1), today)
                val s = settings.value
                files.write(uri, OrderCsv.export(orders, s.money, s.zone))
                orders.size
            }.onSuccess { _messages.emit("已匯出 $it 筆交易") }
                .onFailure { _messages.emit("匯出失敗：${it.message}") }
        }
    }
}

@Composable
fun SettingsScreen(
    isAdmin: Boolean,
    onBack: () -> Unit,
    onDevice: () -> Unit,
    onOperators: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val exportOrders = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportOrders(uri)
    }

    Scaffold(topBar = { AppTopBar("設定", onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding()) {
            item { ClickRow("裝置設定", "印表機、標籤紙、錢箱、掃描器、NFC", Icons.Filled.Devices, onClick = onDevice) }
            if (isAdmin) {
                item { ClickRow("人員管理", "新增收銀員、重設 PIN、權限", Icons.Filled.AdminPanelSettings, onClick = onOperators) }
                item {
                    ClickRow("匯出本月交易明細 CSV", "供會計對帳使用", Icons.Filled.FileDownload) {
                        exportOrders.launch("orders-${LocalDate.now().toString().take(7)}.csv")
                    }
                }
                item { HorizontalDivider() }
                item { SectionHeader("商店資料（列印於收據）") }
                item { StoreEditor(settings.store) { store -> viewModel.update { it.copy(store = store) } } }
                item { HorizontalDivider() }
                item { SectionHeader("稅務與幣別") }
                item { TaxEditor(settings) { tax, symbol, decimals -> viewModel.update { it.copy(tax = tax, currencySymbol = symbol, currencyDecimals = decimals) } } }
                item { HorizontalDivider() }
                item { SectionHeader("會員與收據") }
                item {
                    LoyaltyEditor(settings) { points, code ->
                        if (!OrderNumbers.isValidDeviceCode(code)) {
                            viewModel.update("機台代碼需為 1–4 碼英數字") { it }
                        } else {
                            viewModel.update { it.copy(pointsPerAmount = points, deviceCode = code) }
                        }
                    }
                }
                item {
                    SwitchRow("結帳後自動列印收據", settings.autoPrintReceipt, { v -> viewModel.update { it.copy(autoPrintReceipt = v) } })
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("收據列印份數", Modifier.padding(top = 8.dp))
                        (1..3).forEach { n ->
                            FilterChip(selected = settings.receiptCopies == n, onClick = { viewModel.update { it.copy(receiptCopies = n) } }, label = { Text("$n") })
                        }
                    }
                }
            } else {
                item { Text("商店、稅率與人員設定僅限管理員。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { HorizontalDivider() }
            item { ClickRow("關於", "收銀 POS 1.0.0 · 離線運作，資料儲存在本機", Icons.Filled.Info) {} }
        }
    }
}

@Composable
private fun StoreEditor(store: StoreProfile, onSave: (StoreProfile) -> Unit) {
    var name by remember(store) { mutableStateOf(store.name) }
    var address by remember(store) { mutableStateOf(store.address.orEmpty()) }
    var phone by remember(store) { mutableStateOf(store.phone.orEmpty()) }
    var taxId by remember(store) { mutableStateOf(store.taxId.orEmpty()) }
    var footer by remember(store) { mutableStateOf(store.footer.orEmpty()) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("店名") }, singleLine = true)
        OutlinedTextField(address, { address = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("地址") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(phone, { phone = it.take(20) }, Modifier.weight(1f), label = { Text("電話") }, singleLine = true)
            OutlinedTextField(
                taxId, { taxId = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f), label = { Text("統一編號") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        OutlinedTextField(footer, { footer = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("收據頁尾訊息") }, singleLine = true)
        Button(onClick = { onSave(StoreProfile(name, address, phone, taxId, footer)) }) { Text("儲存商店資料") }
    }
}

@Composable
private fun TaxEditor(settings: PosSettings, onSave: (TaxConfig, String, Int) -> Unit) {
    var mode by remember(settings) { mutableStateOf(settings.tax.mode) }
    var rate by remember(settings) { mutableStateOf((settings.tax.rateBp / 100.0).toString().removeSuffix(".0")) }
    var symbol by remember(settings) { mutableStateOf(settings.currencySymbol) }
    var decimals by remember(settings) { mutableStateOf(settings.currencyDecimals) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaxMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text("稅額${m.label}") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                rate, { rate = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.weight(1f), label = { Text("稅率 %") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = mode != TaxMode.NONE,
            )
            OutlinedTextField(symbol, { symbol = it.take(4) }, Modifier.weight(1f), label = { Text("貨幣符號") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("小數位數", Modifier.padding(top = 8.dp))
            listOf(0, 2).forEach { d -> FilterChip(selected = decimals == d, onClick = { decimals = d }, label = { Text("$d") }) }
        }
        Text(
            "注意：已有交易資料後請勿變更小數位數，否則歷史金額顯示會錯位。台幣請使用 0。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = {
            val bp = ((rate.toDoubleOrNull() ?: 0.0) * 100).toInt().coerceIn(0, 10_000)
            onSave(TaxConfig(mode, bp), symbol, decimals)
        }) { Text("儲存稅務設定") }
    }
}

@Composable
private fun LoyaltyEditor(settings: PosSettings, onSave: (Long, String) -> Unit) {
    var points by remember(settings) { mutableStateOf(settings.pointsPerAmount.toString()) }
    var code by remember(settings) { mutableStateOf(settings.deviceCode) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                points, { points = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = { Text("每消費多少元累積 1 點（0=停用）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(code, { code = it.take(4) }, Modifier.weight(1f), label = { Text("機台代碼（單號用）") }, singleLine = true)
        }
        Button(onClick = { onSave(points.toLongOrNull() ?: 0, code.trim()) }) { Text("儲存") }
    }
}
