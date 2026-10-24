package me.longtai.core.auth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.core.auth.AuthException
import me.longtai.core.auth.Operator
import me.longtai.core.auth.OperatorRepository
import me.longtai.core.auth.Role
import me.longtai.core.auth.Session
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.TextInputDialog
import javax.inject.Inject

@HiltViewModel
class OperatorsViewModel @Inject constructor(
    private val repository: OperatorRepository,
    private val session: Session,
) : ViewModel() {
    val operators: StateFlow<List<Operator>> = repository.operators
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val currentId: Long? get() = session.current.value?.id

    private fun act(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _messages.emit(success)
            } catch (e: AuthException) {
                _messages.emit(e.message ?: "操作失敗")
            }
        }
    }

    fun create(name: String, pin: String, role: Role) = act("已新增 $name") { repository.create(name, pin, role) }
    fun rename(id: Long, name: String) = act("已更新名稱") { repository.rename(id, name) }
    fun resetPin(id: Long, pin: String) = act("PIN 已重設") { repository.resetPin(id, pin) }
    fun setRole(id: Long, role: Role) = act("角色已變更為${role.label}") { repository.setRole(id, role) }
    fun setActive(id: Long, active: Boolean) = act(if (active) "已啟用" else "已停用") {
        if (!active && id == currentId) throw AuthException("無法停用目前登入的帳號")
        repository.setActive(id, active)
    }
}

/** Operator management; reachable by administrators only. */
@Composable
fun OperatorsScreen(onBack: () -> Unit, viewModel: OperatorsViewModel = hiltViewModel()) {
    val operators by viewModel.operators.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Operator?>(null) }
    var renaming by remember { mutableStateOf<Operator?>(null) }
    var resetting by remember { mutableStateOf<Operator?>(null) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { AppTopBar("人員管理", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                text = { Text("新增人員") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(operators, key = { it.id }) { op ->
                ListItem(
                    headlineContent = { Text(op.name + if (op.id == viewModel.currentId) "（目前登入）" else "") },
                    supportingContent = { Text(op.role.label + if (!op.active) " · 已停用" else "") },
                    leadingContent = {
                        Icon(
                            if (op.role == Role.ADMIN) Icons.Filled.AdminPanelSettings else Icons.Filled.Person,
                            contentDescription = null,
                            tint = if (op.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    },
                    modifier = Modifier.clickable { editing = op },
                )
            }
        }
    }

    if (adding) {
        AddOperatorDialog(
            onConfirm = { name, pin, role ->
                viewModel.create(name, pin, role)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    editing?.let { op ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(op.name) },
            text = {
                Column {
                    listOf(
                        "修改名稱" to { renaming = op },
                        "重設 PIN" to { resetting = op },
                        (if (op.role == Role.ADMIN) "改為一般人員" else "設為管理員") to {
                            viewModel.setRole(op.id, if (op.role == Role.ADMIN) Role.STAFF else Role.ADMIN)
                        },
                        (if (op.active) "停用帳號" else "啟用帳號") to { viewModel.setActive(op.id, !op.active) },
                    ).forEach { (label, action) ->
                        TextButton(
                            onClick = {
                                editing = null
                                action()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("關閉") } },
        )
    }

    renaming?.let { op ->
        TextInputDialog(
            title = "修改名稱",
            label = "名稱",
            initial = op.name,
            validate = { if (it.isEmpty() || it.length > 20) "名稱需為 1–20 個字" else null },
            onConfirm = {
                viewModel.rename(op.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    resetting?.let { op ->
        TextInputDialog(
            title = "重設 ${op.name} 的 PIN",
            label = "新 PIN（4–8 位數字）",
            keyboardType = KeyboardType.NumberPassword,
            validate = { if (it.length in 4..8 && it.all(Char::isDigit)) null else "PIN 需為 4–8 位數字" },
            onConfirm = {
                viewModel.resetPin(op.id, it)
                resetting = null
            },
            onDismiss = { resetting = null },
        )
    }
}

@Composable
private fun AddOperatorDialog(onConfirm: (String, String, Role) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.STAFF) }
    val valid = name.isNotBlank() && pin.length in 4..8
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增人員") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth(), label = { Text("名稱") }, singleLine = true)
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                    label = { Text("PIN（4–8 位數字）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Role.entries.forEach { r ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, pin, role) }, enabled = valid) { Text("新增") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
