package me.longtai.smsforward.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.smsforward.data.ForwardLogDao
import me.longtai.smsforward.data.ForwardLogEntity
import me.longtai.smsforward.data.ForwardSettings
import me.longtai.smsforward.data.ForwardSettingsRepository
import me.longtai.smsforward.data.MessageTemplate
import me.longtai.smsforward.sms.ForwardNotifications
import me.longtai.smsforward.sms.SmsForwarder
import me.longtai.smsforward.telegram.TelegramClient
import me.longtai.smsforward.telegram.TelegramServiceController
import javax.inject.Inject

@HiltViewModel
class ForwardViewModel @Inject constructor(
    private val settingsRepository: ForwardSettingsRepository,
    private val forwarder: SmsForwarder,
    private val telegramClient: TelegramClient,
    private val notifications: ForwardNotifications,
    private val serviceController: TelegramServiceController,
    private val logDao: ForwardLogDao,
) : ViewModel() {

    val settings: StateFlow<ForwardSettings> = settingsRepository.settings

    val logs: StateFlow<List<ForwardLogEntity>> =
        logDao.recent(200).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sentCount: StateFlow<Int> =
        logDao.sentCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Enabling is refused until at least one channel is ready, so forwarding never runs half-configured. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !settings.value.anyChannelReady) {
            _messages.tryEmit("請先設定並啟用至少一種轉發方式（簡訊門號或 Telegram）")
            return
        }
        viewModelScope.launch {
            settingsRepository.update { it.copy(enabled = enabled) }
            refreshStatusNotification()
            _messages.emit(if (enabled) "已開始轉發" else "已停止轉發")
        }
    }

    fun setSmsChannel(enabled: Boolean) = updateAndRefresh { it.copy(smsChannelEnabled = enabled) }
    fun setTelegramChannel(enabled: Boolean) = updateAndRefresh { it.copy(telegramChannelEnabled = enabled) }
    fun setTarget(number: String) = updateAndRefresh { it.copy(targetNumber = number) }
    fun setTelegramToken(token: String) = updateAndRefresh { it.copy(telegramBotToken = token) }
    fun setTelegramChatId(chatId: String) = updateAndRefresh { it.copy(telegramChatId = chatId) }
    fun setTemplate(template: String) = updateAndRefresh { it.copy(template = template) }
    fun resetTemplate() = updateAndRefresh { it.copy(template = MessageTemplate.DEFAULT) }
    fun setKeywordFilter(keywords: String) = updateAndRefresh { it.copy(keywordFilter = keywords) }

    /** Inserts a template variable into the template at the end (simple append). */
    fun appendTemplateVariable(variable: String) = updateAndRefresh { it.copy(template = it.template + variable) }

    /** Telegram command listener: only allowed once the bot (token + chat id) is configured. */
    fun setTelegramCommands(enabled: Boolean) {
        if (enabled && !settings.value.telegramConfigured) {
            _messages.tryEmit("請先設定 Bot Token 與 Chat ID")
            return
        }
        viewModelScope.launch {
            settingsRepository.update { it.copy(telegramCommandsEnabled = enabled) }
            serviceController.sync()
            _messages.emit(if (enabled) "已開始接收 Telegram 指令" else "已停止接收 Telegram 指令")
        }
    }

    /** Reads the chat id of the most recent message the bot received, and verifies the token. */
    fun detectChatId() {
        val token = settings.value.telegramBotToken.trim()
        if (token.isBlank()) {
            _messages.tryEmit("請先輸入 Bot Token")
            return
        }
        runBusy {
            telegramClient.detectChatId(token)
                .onSuccess { chatId ->
                    settingsRepository.update { it.copy(telegramChatId = chatId) }
                    _messages.emit("已偵測到 Chat ID：$chatId")
                }
                .onFailure { _messages.emit(it.message ?: "偵測失敗") }
        }
    }

    fun sendTest() = runBusy { _messages.emit(forwarder.sendTest()) }

    fun clearLogs() {
        viewModelScope.launch {
            logDao.clear()
            _messages.emit("已清除紀錄")
        }
    }

    private fun updateAndRefresh(transform: (ForwardSettings) -> ForwardSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
            refreshStatusNotification()
            // Token/chat-id changes may start or stop the command listener.
            serviceController.sync()
        }
    }

    /** Keeps the ongoing banner's text (and visibility) in step with the current settings. */
    private fun refreshStatusNotification() {
        val s = settings.value
        notifications.showStatus(s.enabled, s.statusSummary())
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
