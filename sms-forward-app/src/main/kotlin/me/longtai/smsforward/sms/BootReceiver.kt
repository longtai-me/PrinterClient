package me.longtai.smsforward.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.longtai.smsforward.data.ForwardSettingsRepository
import me.longtai.smsforward.telegram.TelegramServiceController
import javax.inject.Inject

/** After a reboot, restores the forwarding banner and the Telegram command listener if they were on. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: ForwardSettingsRepository

    @Inject lateinit var notifications: ForwardNotifications

    @Inject lateinit var serviceController: TelegramServiceController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                val settings = settingsRepository.current()
                notifications.showStatus(settings.enabled, settings.statusSummary())
                serviceController.sync()
            } finally {
                pending.finish()
            }
        }
    }
}
