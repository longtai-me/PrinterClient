package me.longtai.pos.ui.products

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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
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
import me.longtai.core.common.barcode.BarcodeCheck
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.SwitchRow
import me.longtai.core.ui.components.TextInputDialog
import me.longtai.core.ui.hardware.LocalHardware
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.pos.data.PosPrinter
import me.longtai.pos.data.PosSettingsRepository
import me.longtai.pos.data.ProductRepository
import me.longtai.pos.data.ValidationException
import me.longtai.pos.domain.model.Product
import javax.inject.Inject

data class ProductForm(
    val id: Long = 0,
    val sku: String = "",
    val barcode: String = "",
    val name: String = "",
    val price: String = "",
    val category: String = "",
    val unit: String = "",
    val stock: String = "",
    val lowStock: String = "",
    val taxable: Boolean = true,
    val active: Boolean = true,
)

@HiltViewModel
class ProductEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ProductRepository,
    private val printer: PosPrinter,
    private val settingsRepository: PosSettingsRepository,
) : ViewModel() {
    private val productId: Long = savedState.get<Long>("id") ?: 0L
    val money: MoneyFormat get() = settingsRepository.settings.value.money
    val isNew: Boolean get() = productId == 0L

    private val _form = MutableStateFlow(ProductForm(barcode = savedState.get<String>("barcode").orEmpty()))
    val form: StateFlow<ProductForm> = _form.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    init {
        if (productId != 0L) {
            viewModelScope.launch {
                repository.get(productId)?.let { p ->
                    _form.value = ProductForm(
                        id = p.id, sku = p.sku, barcode = p.barcode.orEmpty(), name = p.name, price = money.formatPlain(p.price),
                        category = p.category.orEmpty(), unit = p.unit.orEmpty(), stock = p.stockQty?.toString().orEmpty(),
                        lowStock = p.lowStockThreshold?.toString().orEmpty(), taxable = p.taxable, active = p.active,
                    )
                }
            }
        } else if (_form.value.barcode.isNotEmpty()) {
            _form.update { it.copy(sku = it.barcode) }
        }
    }

    fun update(transform: (ProductForm) -> ProductForm) = _form.update(transform)

    fun onScan(event: ScanEvent) {
        _form.update { f -> f.copy(barcode = event.code, sku = f.sku.ifEmpty { event.code }) }
    }

    private fun toProduct(): Product? {
        val f = _form.value
        val price = money.parse(f.price)
        if (price == null) {
            _messages.tryEmit("售價格式錯誤")
            return null
        }
        val stockText = f.stock.trim()
        val stock = stockText.toIntOrNull()
        if (stockText.isNotEmpty() && stock == null) {
            _messages.tryEmit("庫存需為整數")
            return null
        }
        val lowText = f.lowStock.trim()
        val low = lowText.toIntOrNull()
        if (lowText.isNotEmpty() && low == null) {
            _messages.tryEmit("安全庫存需為整數")
            return null
        }
        return Product(
            id = f.id, sku = f.sku, barcode = f.barcode.ifBlank { null }, name = f.name, price = price,
            category = f.category, unit = f.unit, stockQty = stock, lowStockThreshold = low, taxable = f.taxable, active = f.active,
        )
    }

    fun save() {
        val product = toProduct() ?: return
        viewModelScope.launch {
            try {
                val saved = repository.save(product)
                _form.update { it.copy(id = saved.id) }
                _messages.emit("已儲存")
                _saved.emit(Unit)
            } catch (e: ValidationException) {
                _messages.emit(e.message ?: "儲存失敗")
            }
        }
    }

    fun printLabel(copies: Int) {
        val product = toProduct() ?: return
        viewModelScope.launch {
            when (val r = printer.priceLabel(product, copies)) {
                is PrinterResult.Ok -> _messages.emit("已列印 $copies 張標籤")
                is PrinterResult.Error -> _messages.emit(r.message)
            }
        }
    }
}

@Composable
fun ProductEditScreen(canEdit: Boolean, onBack: () -> Unit, viewModel: ProductEditViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hardware = LocalHardware.current
    val scope = rememberCoroutineScope()
    var labelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.saved.collect { if (viewModel.isNew) onBack() } }
    ScanEffect(viewModel::onScan)

    val barcodeHint = when {
        form.barcode.isBlank() -> "可直接掃描商品條碼帶入"
        BarcodeCheck.isValidEan13(form.barcode) -> "EAN-13 條碼（檢查碼正確）"
        form.barcode.length == 13 && form.barcode.all(Char::isDigit) -> "注意：EAN-13 檢查碼不正確"
        else -> "將以 Code 128 列印"
    }

    Scaffold(
        topBar = {
            AppTopBar(if (viewModel.isNew) "新增商品" else "商品資料", onBack = onBack) {
                IconButton(onClick = { labelDialog = true }) { Icon(Icons.Filled.Sell, contentDescription = "列印價格標籤") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
            OutlinedTextField(
                form.name, { v -> viewModel.update { it.copy(name = v.take(60)) } }, Modifier.fillMaxWidth(),
                label = { Text("品名 *") }, singleLine = true, enabled = canEdit,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.sku, { v -> viewModel.update { it.copy(sku = v.take(40)) } }, Modifier.weight(1f),
                    label = { Text("SKU / 貨號 *") }, singleLine = true, enabled = canEdit,
                )
                OutlinedTextField(
                    form.price, { v -> viewModel.update { it.copy(price = v.filter { c -> c.isDigit() || c == '.' }.take(10)) } },
                    Modifier.weight(1f), label = { Text("售價 *") }, singleLine = true, enabled = canEdit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            OutlinedTextField(
                form.barcode, { v -> viewModel.update { it.copy(barcode = v.trim().take(48)) } }, Modifier.fillMaxWidth(),
                label = { Text("條碼") }, singleLine = true, enabled = canEdit,
                supportingText = { Text(barcodeHint) },
                trailingIcon = {
                    IconButton(onClick = { scope.launch { hardware.scanner.startScanHead() } }, enabled = canEdit) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "掃描")
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.category, { v -> viewModel.update { it.copy(category = v.take(20)) } }, Modifier.weight(1f),
                    label = { Text("分類") }, singleLine = true, enabled = canEdit,
                )
                OutlinedTextField(
                    form.unit, { v -> viewModel.update { it.copy(unit = v.take(6)) } }, Modifier.weight(1f),
                    label = { Text("單位") }, singleLine = true, enabled = canEdit,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.stock, { v -> viewModel.update { it.copy(stock = v.filter { c -> c.isDigit() || c == '-' }.take(7)) } },
                    Modifier.weight(1f), label = { Text("庫存（空白=不控管）") }, singleLine = true, enabled = canEdit, keyboardOptions = numbers,
                )
                OutlinedTextField(
                    form.lowStock, { v -> viewModel.update { it.copy(lowStock = v.filter(Char::isDigit).take(6)) } },
                    Modifier.weight(1f), label = { Text("安全庫存") }, singleLine = true, enabled = canEdit, keyboardOptions = numbers,
                )
            }
            SwitchRow("應稅商品", form.taxable, { v -> if (canEdit) viewModel.update { it.copy(taxable = v) } })
            SwitchRow("上架銷售", form.active, { v -> if (canEdit) viewModel.update { it.copy(active = v) } })
            if (canEdit) {
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("儲存") }
            } else {
                Text("僅管理員可修改商品資料", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { labelDialog = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("列印價格標籤") }
        }
    }

    if (labelDialog) {
        TextInputDialog(
            title = "列印價格標籤",
            label = "張數（1–50）",
            initial = "1",
            keyboardType = KeyboardType.Number,
            confirmText = "列印",
            validate = { if (it.toIntOrNull() in 1..50) null else "請輸入 1–50" },
            onConfirm = {
                labelDialog = false
                viewModel.printLabel(it.toInt())
            },
            onDismiss = { labelDialog = false },
        )
    }
}
