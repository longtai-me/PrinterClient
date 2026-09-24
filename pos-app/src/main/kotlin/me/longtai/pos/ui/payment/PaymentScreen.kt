package me.longtai.pos.ui.payment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.InfoRow
import me.longtai.core.ui.components.KeypadDisplay
import me.longtai.core.ui.components.NumericKeypad
import me.longtai.core.ui.components.appendAmountKey
import me.longtai.core.ui.theme.StatusColors
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.PaymentMethod

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: PaymentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val money = settings.money
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val completed = state.completed
    // Once the sale is saved, back means "new sale" and must not return to an emptied cart.
    BackHandler(enabled = completed != null) { onDone() }

    if (viewModel.isEmpty && completed == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = { AppTopBar(if (completed == null) "結帳" else "交易完成", onBack = if (completed == null) onBack else onDone) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (completed != null) {
            CompletedContent(completed, money, Modifier.padding(padding), viewModel::reprint, onDone)
        } else {
            PaymentContent(state, money, Modifier.padding(padding), viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentContent(state: PaymentUiState, money: MoneyFormat, modifier: Modifier, viewModel: PaymentViewModel) {
    val tender = state.tender
    run {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow("應收", money.format(tender.total), emphasize = true)
                    InfoRow("已收", money.format(tender.paid))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("尚欠", style = MaterialTheme.typography.titleMedium)
                        Text(
                            money.format(tender.remaining),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (tender.remaining.isPositive) StatusColors.error else StatusColors.success,
                        )
                    }
                }
            }
            tender.payments.forEachIndexed { index, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${p.method.label} ${money.format(p.tendered)}" + (p.reference?.let { " ($it)" } ?: ""),
                        Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.removePayment(index) }) { Icon(Icons.Filled.Close, contentDescription = "移除付款") }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.entries.forEach { m ->
                    FilterChip(selected = state.method == m, onClick = { viewModel.selectMethod(m) }, label = { Text(m.label) })
                }
            }

            KeypadDisplay(state.amountText, placeholder = "收 ${money.format(tender.remaining)}")

            if (state.method == PaymentMethod.CASH) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.cashSuggestions().forEach { amount ->
                        OutlinedButton(onClick = { viewModel.addPayment(amount) }, modifier = Modifier.weight(1f)) {
                            Text(money.format(amount, withSymbol = false), maxLines = 1)
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.reference,
                    onValueChange = viewModel::setReference,
                    label = { Text("授權碼 / 交易序號（選填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            NumericKeypad(
                onKey = { viewModel.setAmountText(appendAmountKey(state.amountText, it, money.decimals)) },
                onBackspace = { viewModel.setAmountText(state.amountText.dropLast(1)) },
                extraKey = if (money.decimals > 0) "." else "00",
                onExtraKey = {
                    val next = if (money.decimals > 0) {
                        appendAmountKey(state.amountText, '.', money.decimals)
                    } else {
                        appendAmountKey(appendAmountKey(state.amountText, '0', 0), '0', 0)
                    }
                    viewModel.setAmountText(next)
                },
                keyHeight = 52,
            )

            Button(
                onClick = { if (tender.isComplete) viewModel.complete() else viewModel.addPayment() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                val label = when {
                    tender.isComplete -> "完成交易"
                    state.amountText.isBlank() -> "${state.method.label}收款 ${money.format(tender.remaining)}"
                    else -> "${state.method.label}收款 ${state.amountText}"
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CompletedContent(order: Order, money: MoneyFormat, modifier: Modifier, onReprint: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StatusColors.success, modifier = Modifier.size(96.dp))
        Text("交易完成", style = MaterialTheme.typography.headlineMedium)
        Text(order.orderNo, color = MaterialTheme.colorScheme.onSurfaceVariant)
        InfoRow("應收", money.format(order.total))
        if (order.change.isPositive) {
            Text("找零", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            Text(
                money.format(order.change),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = StatusColors.success,
            )
        }
        if (order.pointsEarned > 0) Text("會員 ${order.memberName.orEmpty()} 本次累積 ${order.pointsEarned} 點")
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onReprint, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("補印收據") }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("下一筆交易") }
    }
}
