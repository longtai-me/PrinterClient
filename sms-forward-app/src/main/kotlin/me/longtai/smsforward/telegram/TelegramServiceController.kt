package me.longtai.smsforward.telegram

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import me.longtai.smsforward.data.ForwardSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Starts or stops the Telegram command-listener service to match the current settings. */
@Singleton
class TelegramServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: ForwardSettingsRepository,
) {
    suspend fun sync() {
        val intent = Intent(context, TelegramPollingService::class.java)
        if (settingsRepository.current().commandListenerActive) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
    }
}
