package me.longtai.smsforward.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import me.longtai.smsforward.data.ForwardLogDao
import me.longtai.smsforward.data.ForwardLogEntity
import me.longtai.smsforward.data.ForwardSettings
import me.longtai.smsforward.data.ForwardSettingsRepository
import me.longtai.smsforward.data.ForwardStatus
import me.longtai.smsforward.data.PhoneNumbers
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingSms(val from: String, val body: String, val timestamp: Long)

enum class ForwardOutcome { SENT, FAILED, SKIPPED }

/**
 * Applies the owner's settings to an incoming SMS and forwards it via the carrier when it matches.
 * Singleton so the short-lived duplicate guard survives across broadcast receiver instances.
 */
@Singleton
class SmsForwarder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: ForwardSettingsRepository,
    private val logDao: ForwardLogDao,
    private val notifications: ForwardNotifications,
) {
    private val timeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN)
    private val recentKeys = ArrayDeque<Pair<String, Long>>()

    suspend fun handle(sms: IncomingSms): ForwardOutcome {
        val settings = settingsRepository.current()
        if (!settings.enabled) return ForwardOutcome.SKIPPED
        if (!settings.isConfigured) return log(sms, settings.targetNumber, ForwardStatus.SKIPPED, "未設定轉發門號")

        // Loop guard: never forward messages that came from the destination itself.
        if (PhoneNumbers.sameNumber(sms.from, settings.targetNumber)) {
            return log(sms, settings.targetNumber, ForwardStatus.SKIPPED, "來源即為轉發門號，避免迴圈")
        }
        if (settings.keywords.isNotEmpty() && settings.keywords.none { sms.body.contains(it, ignoreCase = true) }) {
            return log(sms, settings.targetNumber, ForwardStatus.SKIPPED, "不符關鍵字條件")
        }
        if (isDuplicate(sms)) return ForwardOutcome.SKIPPED

        val message = compose(sms, settings)
        return try {
            send(settings.targetNumber, message)
            notifications.notifyForwarded(sms.from, settings.targetNumber, success = true)
            log(sms, settings.targetNumber, ForwardStatus.SENT, null)
        } catch (e: Exception) {
            Timber.e(e, "forward failed")
            notifications.notifyForwarded(sms.from, settings.targetNumber, success = false)
            log(sms, settings.targetNumber, ForwardStatus.FAILED, e.message ?: "傳送錯誤")
        }
    }

    private fun compose(sms: IncomingSms, settings: ForwardSettings): String =
        if (settings.includeSenderInfo) {
            "[轉發] ${sms.from} ${timeFormat.format(Date(sms.timestamp))}\n${sms.body}"
        } else {
            sms.body
        }

    private fun send(target: String, message: String) {
        val smsManager = smsManager()
        val parts = smsManager.divideMessage(message)
        if (parts.size <= 1) {
            smsManager.sendTextMessage(target, null, message, null, null)
        } else {
            smsManager.sendMultipartTextMessage(target, null, parts, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    /** Drops the same message if SMS_RECEIVED double-fires within a few seconds. */
    private fun isDuplicate(sms: IncomingSms): Boolean {
        val now = System.currentTimeMillis()
        val key = "${PhoneNumbers.normalize(sms.from)}|${sms.body.hashCode()}"
        while (recentKeys.isNotEmpty() && now - recentKeys.first().second > DEDUP_WINDOW_MS) recentKeys.removeFirst()
        if (recentKeys.any { it.first == key }) return true
        recentKeys.addLast(key to now)
        return false
    }

    private suspend fun log(sms: IncomingSms, target: String, status: ForwardStatus, reason: String?): ForwardOutcome {
        logDao.insert(
            ForwardLogEntity(
                receivedAt = sms.timestamp,
                fromNumber = sms.from,
                targetNumber = target,
                preview = sms.body.take(60),
                status = status.name,
                reason = reason,
            ),
        )
        logDao.purgeOlderThan(System.currentTimeMillis() - LOG_RETENTION_MS)
        return when (status) {
            ForwardStatus.SENT -> ForwardOutcome.SENT
            ForwardStatus.FAILED -> ForwardOutcome.FAILED
            ForwardStatus.SKIPPED -> ForwardOutcome.SKIPPED
        }
    }

    /** Sends a one-off test message so the owner can confirm the destination is correct. */
    suspend fun sendTest(target: String): Result<Unit> = runCatching {
        send(target, "【簡訊轉發】測試訊息，設定成功。")
    }

    private companion object {
        const val DEDUP_WINDOW_MS = 10_000L
        const val LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
