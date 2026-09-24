package me.longtai.core.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.longtai.core.auth.Operator
import me.longtai.core.auth.Role
import me.longtai.core.ui.components.NumericKeypad
import me.longtai.core.ui.components.PinDots

/**
 * Shows first-run setup, the login screen or [content] depending on the session.
 * Logging out disposes [content], which resets the app's navigation state.
 */
@Composable
fun AuthGate(
    appTitle: String,
    viewModel: AuthViewModel = hiltViewModel(),
    content: @Composable (operator: Operator, logout: () -> Unit) -> Unit,
) {
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        when (val g = gate) {
            GateState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            GateState.NeedsSetup -> SetupScreen(appTitle, viewModel)
            GateState.LoggedOut -> LoginScreen(appTitle, viewModel)
            is GateState.LoggedIn -> content(g.operator, viewModel::logout)
        }
    }
}

@Composable
private fun SetupScreen(appTitle: String, viewModel: AuthViewModel) {
    val error by viewModel.setupError.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val pinOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("歡迎使用$appTitle", style = MaterialTheme.typography.headlineSmall)
        Text("首次使用請建立管理員帳號。管理員可管理人員、設定與退貨/作廢等需授權的操作。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth(), label = { Text("管理員名稱") }, singleLine = true)
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
            label = { Text("PIN（4–8 位數字）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = pinOptions,
        )
        OutlinedTextField(
            confirm, { confirm = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
            label = { Text("再次輸入 PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = pinOptions,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.setup(name, pin, confirm) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("建立並登入")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginScreen(appTitle: String, viewModel: AuthViewModel) {
    val operators by viewModel.operators.collectAsStateWithLifecycle()
    val state by viewModel.login.collectAsStateWithLifecycle()
    val selected = operators.firstOrNull { it.id == state.selectedId }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(appTitle, style = MaterialTheme.typography.headlineSmall)
        Text("請選擇人員並輸入 PIN", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            operators.forEach { op ->
                FilterChip(
                    selected = op.id == state.selectedId,
                    onClick = { viewModel.select(op.id) },
                    label = { Text(op.name) },
                    leadingIcon = {
                        Icon(
                            if (op.role == Role.ADMIN) Icons.Filled.AdminPanelSettings else Icons.Filled.Person,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(selected?.name ?: "未選擇人員", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        PinDots(state.pin.length, maxOf(4, state.pin.length))
        Text(
            state.error ?: " ",
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        NumericKeypad(
            onKey = viewModel::pinKey,
            onBackspace = viewModel::pinBackspace,
            extraKey = "登入",
            onExtraKey = viewModel::submitLogin,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.busy) CircularProgressIndicator(Modifier.padding(16.dp))
    }
}
