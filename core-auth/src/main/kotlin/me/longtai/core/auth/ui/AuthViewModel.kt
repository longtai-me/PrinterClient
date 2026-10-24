package me.longtai.core.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.longtai.core.auth.AuthException
import me.longtai.core.auth.AuthResult
import me.longtai.core.auth.Operator
import me.longtai.core.auth.OperatorRepository
import me.longtai.core.auth.Role
import me.longtai.core.auth.Session
import me.longtai.core.common.security.PinHasher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed interface GateState {
    data object Loading : GateState
    data object NeedsSetup : GateState
    data object LoggedOut : GateState
    data class LoggedIn(val operator: Operator) : GateState
}

data class LoginUiState(
    val selectedId: Long? = null,
    val pin: String = "",
    val error: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: OperatorRepository,
    private val session: Session,
) : ViewModel() {

    val gate: StateFlow<GateState> = combine(repository.hasOperators, session.current) { hasOperators, operator ->
        when {
            !hasOperators -> GateState.NeedsSetup
            operator == null -> GateState.LoggedOut
            else -> GateState.LoggedIn(operator)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GateState.Loading)

    val operators: StateFlow<List<Operator>> = repository.operators
        .map { list -> list.filter { it.active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    fun select(operatorId: Long) = _login.update { LoginUiState(selectedId = operatorId) }

    fun pinKey(c: Char) = _login.update { if (it.pin.length >= 8) it else it.copy(pin = it.pin + c, error = null) }

    fun pinBackspace() = _login.update { it.copy(pin = it.pin.dropLast(1), error = null) }

    fun submitLogin() {
        val state = _login.value
        val id = state.selectedId ?: run {
            _login.update { it.copy(error = "請先選擇人員") }
            return
        }
        if (state.pin.length < 4 || state.busy) return
        _login.update { it.copy(busy = true) }
        viewModelScope.launch {
            val error = when (val result = repository.authenticate(id, state.pin)) {
                is AuthResult.Success -> {
                    session.login(result.operator)
                    null
                }
                is AuthResult.WrongPin -> "PIN 錯誤，剩餘 ${result.attemptsLeft} 次"
                is AuthResult.Locked -> "錯誤次數過多，已鎖定至 ${SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(result.untilMs))}"
                AuthResult.Inactive -> "此帳號已停用"
                AuthResult.NotFound -> "找不到人員"
            }
            _login.update { LoginUiState(selectedId = id, error = error) }
        }
    }

    fun setup(name: String, pin: String, confirm: String) {
        _setupError.value = null
        when {
            name.isBlank() -> _setupError.value = "請輸入管理員名稱"
            !PinHasher.isValidPin(pin) -> _setupError.value = "PIN 需為 4–8 位數字"
            pin != confirm -> _setupError.value = "兩次輸入的 PIN 不一致"
            else -> viewModelScope.launch {
                try {
                    session.login(repository.create(name, pin, Role.ADMIN))
                } catch (e: AuthException) {
                    _setupError.value = e.message
                }
            }
        }
    }

    fun logout() {
        session.logout()
        _login.value = LoginUiState()
    }
}
