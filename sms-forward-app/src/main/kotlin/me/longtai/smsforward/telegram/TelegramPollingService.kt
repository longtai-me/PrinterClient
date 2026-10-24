package me.longtai.smsforward.telegram

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.longtai.smsforward.data.ForwardSettingsRepository
import me.longtai.smsforward.sms.ForwardNotifications
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that long-polls the Telegram bot for commands and executes them. It only acts
 * on messages from the owner's configured chat id, shows a persistent notification the whole time
 * it runs (transparency), and stops itself when the feature is turned off.
 */
@AndroidEntryPoint
class TelegramPollingService : Service() {

    @Inject lateinit var settingsRepository: ForwardSettingsRepository

    @Inject lateinit var telegramClient: TelegramClient

    @Inject lateinit var executor: TelegramCommandExecutor

    @Inject lateinit var notifications: ForwardNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (loop?.isActive != true) loop = scope.launch { pollLoop() }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, ForwardNotifications.ID_COMMAND, notifications.commandServiceNotification(), type)
    }

    private suspend fun pollLoop() {
        // On a fresh start (no stored offset) the first batch is treated as backlog and skipped,
        // so a command sitting in the chat from before the feature was enabled is not executed.
        var skipBacklog = settingsRepository.lastUpdateId() == 0L
        while (scope.isActive) {
            val settings = settingsRepository.current()
            if (!settings.commandListenerActive) {
                stopSelf()
                return
            }
            val offset = settingsRepository.lastUpdateId()
            telegramClient.getUpdates(settings.telegramBotToken, offset, LONG_POLL_SECONDS)
                .onSuccess { updates ->
                    for (update in updates) {
                        settingsRepository.setLastUpdateId(update.updateId + 1)
                        // Authorisation: only act on messages from the owner's own chat.
                        if (!skipBacklog && update.text.isNotBlank() && sameChat(update.chatId, settings.telegramChatId)) {
                            runCatching { executor.execute(update.text, settings) }
                                .onFailure { Timber.e(it, "command execution failed") }
                        }
                    }
                    skipBacklog = false
                }
                .onFailure {
                    Timber.w("getUpdates failed: %s", it.message)
                    delay(ERROR_BACKOFF_MS)
                }
        }
    }

    private fun sameChat(a: String, b: String): Boolean = a.isNotBlank() && a.trim() == b.trim()

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val LONG_POLL_SECONDS = 50
        const val ERROR_BACKOFF_MS = 5_000L
    }
}
