package me.longtai.pos.ui.products

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.EmptyState
import me.longtai.core.ui.files.CsvFiles
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.core.ui.theme.StatusColors
import me.longtai.pos.data.PosSettings
import me.longtai.pos.data.PosSettingsRepository
import me.longtai.pos.data.ProductRepository
import me.longtai.pos.domain.model.Product
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProductsViewModel @Inject constructor(
    private val repository: ProductRepository,
    private val files: CsvFiles,
    settingsRepository: PosSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<PosSettings> = settingsRepository.settings
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _showInactive = MutableStateFlow(false)
    val showInactive: StateFlow<Boolean> = _showInactive.asStateFlow()

    val products: StateFlow<List<Product>> = combine(_query, _showInactive) { q, inactive -> q to inactive }
        .flatMapLatest { (q, inactive) -> repository.search(q, inactive) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _importReport = MutableStateFlow<ProductRepository.ImportSummary?>(null)
    val importReport: StateFlow<ProductRepository.ImportSummary?> = _importReport.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    fun toggleInactive() {
        _showInactive.value = !_showInactive.value
    }

    fun onScan(event: ScanEvent) = setQuery(event.code)

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching { files.write(uri, repository.exportCsv()) }
            .onSuccess { _messages.emit("已匯出商品資料") }
            .onFailure { _messages.emit("匯出失敗：${it.message}") }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        runCatching { repository.importCsv(files.read(uri)) }
            .onSuccess { _importReport.value = it }
            .onFailure { _messages.emit("匯入失敗：${it.message}") }
    }

    fun dismissReport() {
        _importReport.value = null
    }
}

@Composable
fun ProductsScreen(canEdit: Boolean, onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: ProductsViewModel = hiltViewModel()) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val showInactive by viewModel.showInactive.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val report by viewModel.importReport.collectAsStateWithLifecycle()
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

    Scaffold(
        topBar = {
            AppTopBar("商品管理", onBack = onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (showInactive) "隱藏已下架商品" else "顯示已下架商品") },
                            onClick = {
                                menu = false
                                viewModel.toggleInactive()
                            },
                        )
                        if (canEdit) {
                            DropdownMenuItem(
                                text = { Text("匯入 CSV") },
                                onClick = {
                                    menu = false
                                    importLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream"))
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("匯出 CSV") },
                            onClick = {
                                menu = false
                                exportLauncher.launch("products-${LocalDate.now()}.csv")
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (canEdit) FloatingActionButton(onClick = { onOpen(0) }) { Icon(Icons.Filled.Add, contentDescription = "新增商品") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("搜尋品名 / SKU / 條碼，或直接掃描") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (products.isEmpty()) {
                EmptyState(Icons.Filled.Inventory2, "沒有商品", if (canEdit) "點右下角新增，或從選單匯入 CSV" else null)
            } else {
                LazyColumn {
                    items(products, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    p.name + if (!p.active) "（已下架）" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(listOfNotNull(p.sku, p.barcode, p.category, p.stockQty?.let { "庫存 $it" }).joinToString(" · "))
                            },
                            leadingContent = if (p.isLowStock) {
                                { Icon(Icons.Filled.Warning, contentDescription = "低庫存", tint = StatusColors.warning) }
                            } else {
                                null
                            },
                            trailingContent = { Text(settings.money.format(p.price), style = MaterialTheme.typography.titleMedium) },
                            modifier = Modifier.clickable { onOpen(p.id) },
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
                    Text("新增 ${r.inserted} 筆、更新 ${r.updated} 筆" + if (r.errors.isNotEmpty()) "，${r.errors.size} 筆有誤：" else "")
                    r.errors.take(20).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (r.errors.size > 20) Text("…以及另外 ${r.errors.size - 20} 筆")
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissReport) { Text("確定") } },
        )
    }
}
