package me.longtai.smsforward.telegram

import me.longtai.smsforward.data.ForwardChannel
import me.longtai.smsforward.data.ForwardLogDao
import me.longtai.smsforward.data.ForwardLogEntity
import me.longtai.smsforward.data.ForwardSettings
import me.longtai.smsforward.data.ForwardSettingsRepository
import me.longtai.smsforward.data.ForwardStatus
import me.longtai.smsforward.sms.SmsSender
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a command that arrived from the owner's Telegram chat: sends the requested SMS from this
 * device, logs it, and replies to the chat with the outcome. The caller is responsible for having
 * already checked that the update came from the authorised chat id.
 */
@Singleton
class TelegramCommandExecutor @Inject constructor(
    private val smsSender: SmsSender,
    private val telegramClient: TelegramClient,
    private val settingsRepository: ForwardSettingsRepository,
    private val logDao: ForwardLogDao,
) {
    suspend fun execute(text: String, settings: ForwardSettings) {
        when (val command = TelegramCommandParser.parse(text)) {
            is TelegramCommand.SendSms -> sendSms(command.number, command.text, settings)
            is TelegramCommand.Reply -> {
                val target = settingsRepository.lastInboundSender()
                if (target == null) {
                    reply(settings, "沒有可回覆的簡訊（尚未收到任何簡訊）")
                } else {
                    sendSms(target, command.text, settings)
                }
            }
            TelegramCommand.Help -> reply(settings, TelegramCommandParser.HELP_TEXT)
            TelegramCommand.Ping -> reply(settings, "pong ✅")
            is TelegramCommand.Invalid -> reply(settings, "⚠️ ${command.reason}")
            TelegramCommand.NotACommand -> Unit
        }
    }

    private suspend fun sendSms(number: String, body: String, settings: ForwardSettings) {
        val outcome = runCatching { smsSender.send(number, body) }
        val status = if (outcome.isSuccess) ForwardStatus.SENT else ForwardStatus.FAILED
        logDao.insert(
            ForwardLogEntity(
                receivedAt = System.currentTimeMillis(),
                fromNumber = "Telegram 指令",
                channel = ForwardChannel.SMS.name,
                destination = number,
                preview = body.take(60),
                status = status.name,
                reason = outcome.exceptionOrNull()?.message,
            ),
        )
        reply(
            settings,
            if (outcome.isSuccess) "✅ 已發送簡訊給 $number" else "❌ 發送失敗：${outcome.exceptionOrNull()?.message ?: "未知錯誤"}",
        )
    }

    private suspend fun reply(settings: ForwardSettings, message: String) {
        telegramClient.sendMessage(settings.telegramBotToken, settings.telegramChatId, message)
            .onFailure { Timber.w("telegram reply failed: %s", it.message) }
    }
}
