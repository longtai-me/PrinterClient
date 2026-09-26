package me.longtai.smsforward.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.smsforward.data.ForwardLogDao
import me.longtai.smsforward.data.ForwardLogEntity
import me.longtai.smsforward.data.ForwardSettings
import me.longtai.smsforward.data.ForwardSettingsRepository
import me.longtai.smsforward.sms.ForwardNotifications
import me.longtai.smsforward.sms.SmsForwarder
import javax.inject.Inject

@HiltViewModel
class ForwardViewModel @Inject constructor(
    private val settingsRepository: ForwardSettingsRepository,
    private val forwarder: SmsForwarder,
    private val notifications: ForwardNotifications,
    private val logDao: ForwardLogDao,
) : ViewModel() {

    val settings: StateFlow<ForwardSettings> = settingsRepository.settings

    val logs: StateFlow<List<ForwardLogEntity>> =
        logDao.recent(200).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sentCount: StateFlow<Int> =
        logDao.sentCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Enabling is refused until a destination number is set, so forwarding can never run half-configured. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !settings.value.isConfigured) {
            _messages.tryEmit("請先輸入轉發門號")
            return
        }
        viewModelScope.launch {
            settingsRepository.update { it.copy(enabled = enabled) }
            notifications.showStatus(enabled, settings.value.targetNumber)
            _messages.emit(if (enabled) "已開始轉發" else "已停止轉發")
        }
    }

    fun setTarget(number: String) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(targetNumber = number) }
            if (settings.value.enabled) notifications.showStatus(true, number.trim())
        }
    }

    fun setIncludeSenderInfo(include: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(includeSenderInfo = include) } }
    }

    fun setKeywordFilter(keywords: String) {
        viewModelScope.launch { settingsRepository.update { it.copy(keywordFilter = keywords) } }
    }

    fun sendTest() {
        val target = settings.value.targetNumber.trim()
        if (target.isEmpty()) {
            _messages.tryEmit("請先輸入轉發門號")
            return
        }
        viewModelScope.launch {
            forwarder.sendTest(target)
                .onSuccess { _messages.emit("測試訊息已送出至 $target") }
                .onFailure { _messages.emit("測試失敗：${it.message}") }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logDao.clear()
            _messages.emit("已清除紀錄")
        }
    }
}
