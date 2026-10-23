package me.longtai.smsforward.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import me.longtai.smsforward.data.ForwardChannel
import me.longtai.smsforward.data.ForwardLogDao
import me.longtai.smsforward.data.ForwardLogEntity
import me.longtai.smsforward.data.ForwardSettings
import me.longtai.smsforward.data.ForwardSettingsRepository
import me.longtai.smsforward.data.ForwardStatus
import me.longtai.smsforward.data.PhoneNumbers
import me.longtai.smsforward.telegram.TelegramClient
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingSms(val from: String, val body: String, val timestamp: Long)

/**
 * Applies the owner's settings to an incoming SMS and forwards it through every enabled channel
 * (carrier SMS and/or a Telegram bot). Singleton so the short-lived duplicate guard survives
 * across broadcast receiver instances.
 */
@Singleton
class SmsForwarder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: ForwardSettingsRepository,
    private val telegramClient: TelegramClient,
    private val logDao: ForwardLogDao,
    private val notifications: ForwardNotifications,
) {
    private val timeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN)
    private val recentKeys = ArrayDeque<Pair<String, Long>>()

    suspend fun handle(sms: IncomingSms) {
        val settings = settingsRepository.current()
        if (!settings.enabled) return
        if (settings.keywords.isNotEmpty() && settings.keywords.none { sms.body.contains(it, ignoreCase = true) }) {
            log(sms, ForwardChannel.GENERAL, "—", ForwardStatus.SKIPPED, "不符關鍵字條件")
            return
        }
        if (isDuplicate(sms)) return

        val text = compose(sms, settings)
        if (settings.smsChannelEnabled) forwardViaSms(sms, settings, text)
        if (settings.telegramChannelEnabled) forwardViaTelegram(sms, settings, text)
    }

    private suspend fun forwardViaSms(sms: IncomingSms, settings: ForwardSettings, text: String) {
        val target = settings.targetNumber
        if (target.isBlank()) {
            log(sms, ForwardChannel.SMS, "—", ForwardStatus.SKIPPED, "未設定轉發門號")
            return
        }
        // Loop guard: never forward messages that came from the SMS destination itself.
        if (PhoneNumbers.sameNumber(sms.from, target)) {
            log(sms, ForwardChannel.SMS, target, ForwardStatus.SKIPPED, "來源即為轉發門號，避免迴圈")
            return
        }
        try {
            sendSms(target, text)
            notifications.notifyForwarded(sms.from, "簡訊 $target", success = true)
            log(sms, ForwardChannel.SMS, target, ForwardStatus.SENT, null)
        } catch (e: Exception) {
            Timber.e(e, "sms forward failed")
            notifications.notifyForwarded(sms.from, "簡訊 $target", success = false)
            log(sms, ForwardChannel.SMS, target, ForwardStatus.FAILED, e.message ?: "傳送錯誤")
        }
    }

    private suspend fun forwardViaTelegram(sms: IncomingSms, settings: ForwardSettings, text: String) {
        if (settings.telegramBotToken.isBlank() || settings.telegramChatId.isBlank()) {
            log(sms, ForwardChannel.TELEGRAM, "Telegram", ForwardStatus.SKIPPED, "未設定 Bot Token 或 Chat ID")
            return
        }
        telegramClient.sendMessage(settings.telegramBotToken, settings.telegramChatId, text)
            .onSuccess {
                notifications.notifyForwarded(sms.from, "Telegram", success = true)
                log(sms, ForwardChannel.TELEGRAM, "Telegram", ForwardStatus.SENT, null)
            }
            .onFailure { e ->
                Timber.e(e, "telegram forward failed")
                notifications.notifyForwarded(sms.from, "Telegram", success = false)
                log(sms, ForwardChannel.TELEGRAM, "Telegram", ForwardStatus.FAILED, e.message ?: "傳送錯誤")
            }
    }

    private fun compose(sms: IncomingSms, settings: ForwardSettings): String =
        if (settings.includeSenderInfo) {
            "[轉發] ${sms.from} ${timeFormat.format(Date(sms.timestamp))}\n${sms.body}"
        } else {
            sms.body
        }

    private fun sendSms(target: String, message: String) {
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

    private suspend fun log(sms: IncomingSms, channel: ForwardChannel, destination: String, status: ForwardStatus, reason: String?) {
        logDao.insert(
            ForwardLogEntity(
                receivedAt = sms.timestamp,
                fromNumber = sms.from,
                channel = channel.name,
                destination = destination,
                preview = sms.body.take(60),
                status = status.name,
                reason = reason,
            ),
        )
        logDao.purgeOlderThan(System.currentTimeMillis() - LOG_RETENTION_MS)
    }

    /** Sends a one-off test message through every ready channel so the owner can confirm setup. */
    suspend fun sendTest(): String {
        val settings = settingsRepository.current()
        val results = mutableListOf<String>()
        if (settings.smsReady) {
            runCatching { sendSms(settings.targetNumber, TEST_MESSAGE) }
                .onSuccess { results += "簡訊已送出" }
                .onFailure { results += "簡訊失敗：${it.message}" }
        }
        if (settings.telegramReady) {
            telegramClient.sendMessage(settings.telegramBotToken, settings.telegramChatId, TEST_MESSAGE)
                .onSuccess { results += "Telegram 已送出" }
                .onFailure { results += "Telegram 失敗：${it.message}" }
        }
        return if (results.isEmpty()) "請先設定並啟用至少一種轉發方式" else results.joinToString("；")
    }

    private companion object {
        const val DEDUP_WINDOW_MS = 10_000L
        const val LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val TEST_MESSAGE = "【簡訊轉發】測試訊息，設定成功。"
    }
}
