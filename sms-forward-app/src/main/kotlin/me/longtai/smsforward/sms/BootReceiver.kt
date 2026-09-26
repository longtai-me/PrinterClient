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
import javax.inject.Inject

/** After a reboot, restores the persistent "forwarding is active" banner if it was enabled. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: ForwardSettingsRepository

    @Inject lateinit var notifications: ForwardNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                val settings = settingsRepository.current()
                notifications.showStatus(settings.enabled, settings.targetNumber)
            } finally {
                pending.finish()
            }
        }
    }
}
