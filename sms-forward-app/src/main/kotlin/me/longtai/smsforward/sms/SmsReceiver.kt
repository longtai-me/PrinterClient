package me.longtai.smsforward.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives incoming SMS and hands each reconstructed message to [SmsForwarder]. Reading the
 * message here does not delete or hide it — the original still arrives in the device's SMS inbox.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var forwarder: SmsForwarder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (messages.isEmpty()) return

        // A long SMS arrives as multiple parts; join them back into one message per sender.
        val from = messages.first().displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val timestamp = messages.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (body.isBlank()) return

        val pending = goAsync()
        scope.launch {
            try {
                forwarder.handle(IncomingSms(from = from, body = body, timestamp = timestamp))
            } catch (e: Exception) {
                Timber.e(e, "onReceive failed")
            } finally {
                pending.finish()
            }
        }
    }
}
