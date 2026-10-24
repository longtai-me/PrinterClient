package me.longtai.pos.ui.sale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.longtai.core.auth.Operator
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.hardware.printer.PrinterState
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.ConfirmDialog
import me.longtai.core.ui.components.EmptyState
import me.longtai.core.ui.components.KeypadDisplay
import me.longtai.core.ui.components.NumericKeypad
import me.longtai.core.ui.components.appendAmountKey
import me.longtai.core.ui.hardware.LocalHardware
import me.longtai.core.ui.hardware.NfcEffect
import me.longtai.core.ui.hardware.ScanEffect
import me.longtai.core.ui.hardware.rememberCameraScanner
import me.longtai.core.ui.theme.StatusColors
import me.longtai.pos.domain.cart.CartLine
import me.longtai.pos.domain.cart.CartTotals
import me.longtai.pos.domain.cart.LineTotals
import me.longtai.pos.domain.model.Discount
import me.longtai.pos.ui.Routes
import me.longtai.pos.ui.common.DiscountDialog
import me.longtai.pos.ui.common.describe

@Composable
fun SaleScreen(
    operator: Operator,
    onPay: () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: SaleViewModel = hiltViewModel(),
) {
    val shift by viewModel.shift.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val printerState by viewModel.printerState.collectAsStateWithLifecycle()
    val lowStock by viewModel.lowStockCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var unknownCode by remember { mutableStateOf<String?>(null) }
    var unknownCard by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SaleEvent.Message -> snackbar.showSnackbar(event.text)
                is SaleEvent.UnknownCode -> unknownCode = event.code
                is SaleEvent.UnknownCard -> unknownCard = event.uid
            }
        }
    }
    if (shift is ShiftState.Open) {
        ScanEffect(viewModel::onScan)
        NfcEffect(viewModel::onNfc)
    }

    Scaffold(
        topBar = {
            val subtitle = (shift as? ShiftState.Open)?.let { " · 班別 #${it.shift.id}" } ?: ""
            AppTopBar(title = "收銀 · ${operator.name}$subtitle") {
                IconButton(onClick = { onNavigate(Routes.DEVICE) }) {
                    val color = when (printerState) {
                        is PrinterState.Connected -> StatusColors.success
                        PrinterState.Simulated, PrinterState.Connecting -> StatusColors.warning
                        else -> StatusColors.error
                    }
                    Icon(Icons.Filled.Print, contentDescription = "印表機狀態", tint = color)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        BadgedBox(badge = { if (lowStock > 0) Badge { Text("$lowStock") } }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "選單")
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val go: (() -> Unit) -> () -> Unit = { action ->
                            {
                                menuOpen = false
                                action()
                            }
                        }
                        MenuItem(if (lowStock > 0) "商品管理（$lowStock 項低庫存）" else "商品管理", Icons.Filled.Inventory2, go { onNavigate(Routes.PRODUCTS) })
                        MenuItem("會員管理", Icons.Filled.People, go { onNavigate(Routes.MEMBERS) })
                        MenuItem("交易紀錄 / 退貨", Icons.Filled.History, go { onNavigate(Routes.ORDERS) })
                        MenuItem("班別與報表", Icons.Filled.Assessment, go { onNavigate(Routes.SHIFT) })
                        MenuItem("設定", Icons.Filled.Settings, go { onNavigate(Routes.SETTINGS) })
                        HorizontalDivider()
                        MenuItem("登出", Icons.Filled.PowerSettingsNew, go(onLogout))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (shift) {
                ShiftState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ShiftState.Closed -> OpenShiftPanel(settings.money, viewModel::openShift)
                is ShiftState.Open -> RegisterContent(viewModel, settings.money, onPay)
            }
        }
    }

    unknownCode?.let { code ->
        ConfirmDialog(
            title = "查無商品",
            message = "條碼「$code」不在商品資料中。" + if (operator.isAdmin) "要立即新增此商品嗎？" else "請洽管理員建檔。",
            confirmText = if (operator.isAdmin) "新增商品" else "知道了",
            onConfirm = {
                unknownCode = null
                if (operator.isAdmin) onNavigate(Routes.productEdit(0, code))
            },
            onDismiss = { unknownCode = null },
        )
    }
    unknownCard?.let { uid ->
        ConfirmDialog(
            title = "未登記的卡片",
            message = "卡號 $uid 尚未綁定會員，要建立新會員嗎？",
            confirmText = "新增會員",
            onConfirm = {
                unknownCard = null
                onNavigate(Routes.memberEdit(0, uid))
            },
            onDismiss = { unknownCard = null },
        )
    }
}

@Composable
private fun OpenShiftPanel(money: MoneyFormat, onOpen: (me.longtai.core.common.money.Money) -> Unit) {
    var amount by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("尚未開班", style = MaterialTheme.typography.headlineSmall)
        Text("請清點錢櫃並輸入開班零用金", color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeypadDisplay(amount, placeholder = money.format(me.longtai.core.common.money.Money.ZERO))
        NumericKeypad(
            onKey = { amount = appendAmountKey(amount, it, money.decimals) },
            onBackspace = { amount = amount.dropLast(1) },
            extraKey = if (money.decimals > 0) "." else "",
            onExtraKey = { amount = appendAmountKey(amount, '.', money.decimals) },
        )
        Button(
            onClick = { onOpen(money.parse(amount.ifEmpty { "0" }) ?: me.longtai.core.common.money.Money.ZERO) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("開班") }
    }
}

@Composable
private fun RegisterContent(viewModel: SaleViewModel, money: MoneyFormat, onPay: () -> Unit) {
    val cart by viewModel.cart.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val hardware = LocalHardware.current
    val scope = rememberCoroutineScope()
    val openCamera = rememberCameraScanner()
    var editing by remember { mutableStateOf<CartLine?>(null) }
    var orderDiscount by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var memberDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("掃描或輸入條碼 / 品名") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "清除") }
                    }
                    IconButton(onClick = { scope.launch { hardware.scanner.startScanHead() } }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "掃描")
                    }
                    IconButton(onClick = openCamera) { Icon(Icons.Filled.CameraAlt, contentDescription = "相機掃描") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submitQuery() }),
        )

        if (query.isNotBlank()) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 260.dp)) {
                if (results.isEmpty()) {
                    Text("沒有符合的商品", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        items(results, key = { it.id }) { p ->
                            ListItem(
                                headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(listOfNotNull(p.sku, p.barcode, p.stockQty?.let { "庫存 $it" }).joinToString(" · ")) },
                                trailingContent = { Text(money.format(p.price), style = MaterialTheme.typography.titleMedium) },
                                modifier = Modifier.clickable {
                                    viewModel.add(p)
                                    viewModel.onQueryChange("")
                                },
                            )
                        }
                    }
                }
            }
        }

        MemberBar(
            name = cart.member?.let { m -> "${m.name}（${m.memberNo}）" + if (m.discountBp > 0) " · ${describe(Discount.Percent(m.discountBp), money)}" else "" },
            points = cart.member?.points,
            onClick = { memberDialog = true },
            onClear = viewModel::detachMember,
        )

        Box(Modifier.weight(1f)) {
            if (cart.isEmpty) {
                EmptyState(Icons.Filled.ShoppingCart, "尚無商品", "掃描商品條碼、輸入品名搜尋，或感應會員卡")
            } else {
                LazyColumn {
                    items(cart.lines, key = { it.lineId }) { line ->
                        CartLineRow(
                            line = line,
                            totals = totals.line(line.lineId),
                            money = money,
                            onClick = { editing = line },
                            onPlus = { viewModel.increment(line.lineId) },
                            onMinus = { viewModel.decrement(line.lineId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        TotalsPanel(totals, money)

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { orderDiscount = true }, enabled = !cart.isEmpty, modifier = Modifier.height(56.dp)) { Text("折扣") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = !cart.isEmpty, modifier = Modifier.height(56.dp)) { Text("清空") }
            Button(onClick = onPay, enabled = !cart.isEmpty, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("結帳 ${money.format(totals.total)}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    editing?.let { cart.line(it.lineId) }?.let { line ->
        LineEditDialog(
            line = line,
            money = money,
            onQuantity = { viewModel.setQuantity(line.lineId, it) },
            onDiscount = { viewModel.setLineDiscount(line.lineId, it) },
            onRemove = {
                viewModel.remove(line.lineId)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    if (orderDiscount) {
        DiscountDialog(
            title = "整單折扣",
            current = cart.orderDiscount,
            money = money,
            onApply = {
                viewModel.setOrderDiscount(it)
                orderDiscount = false
            },
            onDismiss = { orderDiscount = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "清空購物車",
            message = "確定要移除所有商品嗎？",
            confirmText = "清空",
            destructive = true,
            onConfirm = {
                viewModel.clearCart()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (memberDialog) {
        me.longtai.core.ui.components.TextInputDialog(
            title = "會員",
            label = "會員編號或手機號碼（也可直接感應會員卡）",
            confirmText = "查詢",
            onConfirm = {
                viewModel.lookupMember(it)
                memberDialog = false
            },
            onDismiss = { memberDialog = false },
        )
    }
}

@Composable
private fun MemberBar(name: String?, points: Long?, onClick: () -> Unit, onClear: () -> Unit) {
    Surface(
        color = if (name != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CardMembership, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name ?: "感應會員卡或點此輸入會員", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (points != null) Text("點數 $points", style = MaterialTheme.typography.bodySmall)
            }
            if (name != null) {
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "移除會員") }
            }
        }
    }
}

@Composable
private fun CartLineRow(
    line: CartLine,
    totals: LineTotals?,
    money: MoneyFormat,
    onClick: () -> Unit,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.product.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val discount = line.discount?.let { " · ${describe(it, money)}" } ?: ""
            Text("${money.format(line.unitPrice)} × ${line.quantity}$discount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, contentDescription = "減少") }
        Text("${line.quantity}", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, contentDescription = "增加") }
        Text(
            money.format(totals?.net ?: line.unitPrice * line.quantity),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(84.dp),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun TotalsPanel(totals: CartTotals, money: MoneyFormat) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("共 ${totals.itemCount} 件 · 小計 ${money.format(totals.subtotal)}", style = MaterialTheme.typography.bodyMedium)
                if (totals.totalDiscount.isPositive) {
                    Text("折扣 -${money.format(totals.totalDiscount)}", color = StatusColors.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(if (totals.tax.isPositive) "含稅 ${money.format(totals.tax)}" else "", style = MaterialTheme.typography.bodySmall)
                Text("應收 ${money.format(totals.total)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LineEditDialog(
    line: CartLine,
    money: MoneyFormat,
    onQuantity: (Int) -> Unit,
    onDiscount: (Discount?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var discountDialog by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.product.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("單價 ${money.format(line.unitPrice)}" + (line.discount?.let { " · ${describe(it, money)}" } ?: ""))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onQuantity(line.quantity - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "減少") }
                    Text("${line.quantity}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                    OutlinedButton(onClick = { onQuantity(line.quantity + 1) }) { Icon(Icons.Filled.Add, contentDescription = "增加") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10).forEach { step ->
                        OutlinedButton(onClick = { onQuantity(line.quantity + step) }) { Text("+$step") }
                    }
                }
                OutlinedButton(onClick = { discountDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("單品折扣") }
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text("移除此商品", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
    if (discountDialog) {
        DiscountDialog(
            title = "單品折扣",
            current = line.discount,
            money = money,
            onApply = {
                onDiscount(it)
                discountDialog = false
            },
            onDismiss = { discountDialog = false },
        )
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
