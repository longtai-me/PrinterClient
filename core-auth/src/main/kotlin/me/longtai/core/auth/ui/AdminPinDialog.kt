package me.longtai.core.auth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import me.longtai.core.auth.Operator
import me.longtai.core.auth.OperatorRepository
import me.longtai.core.auth.Session
import me.longtai.core.ui.components.NumericKeypad
import me.longtai.core.ui.components.PinDots
import javax.inject.Inject

@HiltViewModel
class AdminAuthViewModel @Inject constructor(
    private val repository: OperatorRepository,
    private val session: Session,
) : ViewModel() {
    val currentOperator: Operator? get() = session.current.value
    suspend fun authorize(pin: String): Operator? = repository.authorizeAdmin(pin)
}

/**
 * Supervisor override. If the signed-in operator is an admin the action is authorised
 * immediately; otherwise an admin must enter their PIN.
 */
@Composable
fun AdminAuthorization(
    reason: String,
    onAuthorized: (admin: Operator) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AdminAuthViewModel = hiltViewModel(),
) {
    val current = viewModel.currentOperator
    if (current != null && current.isAdmin) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onAuthorized(current) }
        return
    }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (pin.length < 4 || busy) return
        busy = true
        scope.launch {
            val admin = viewModel.authorize(pin)
            busy = false
            if (admin != null) onAuthorized(admin) else {
                error = "管理員 PIN 錯誤"
                pin = ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要管理員授權") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                PinDots(pin.length, maxOf(4, pin.length), Modifier.padding(vertical = 12.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                NumericKeypad(
                    onKey = { c ->
                        if (pin.length < 8) pin += c
                        error = null
                    },
                    onBackspace = { pin = pin.dropLast(1) },
                    extraKey = "確認",
                    onExtraKey = ::submit,
                    keyHeight = 48,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
