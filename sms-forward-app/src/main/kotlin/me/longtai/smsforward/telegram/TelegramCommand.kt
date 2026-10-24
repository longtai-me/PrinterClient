package me.longtai.smsforward.telegram

/** A command the owner sends to the bot to drive the device. */
sealed interface TelegramCommand {
    /** Send an SMS to an explicit number. */
    data class SendSms(val number: String, val text: String) : TelegramCommand

    /** Reply by SMS to whoever sent the most recent inbound SMS. */
    data class Reply(val text: String) : TelegramCommand

    data object Help : TelegramCommand
    data object Ping : TelegramCommand

    /** A command was recognised but its arguments were wrong. */
    data class Invalid(val reason: String) : TelegramCommand

    /** Not a command (plain chat); ignored silently. */
    data object NotACommand : TelegramCommand
}

object TelegramCommandParser {

    const val HELP_TEXT =
        "可用指令：\n" +
            "/sms <門號> <內容> — 發簡訊給指定門號\n" +
            "/reply <內容> — 回覆最後一封收到的簡訊\n" +
            "/ping — 測試連線\n" +
            "/help — 顯示說明"

    fun parse(raw: String): TelegramCommand {
        val text = raw.trim()
        if (text.isEmpty() || !text.startsWith("/")) return TelegramCommand.NotACommand

        val firstSpace = text.indexOfFirst { it.isWhitespace() }
        val command = (if (firstSpace < 0) text else text.substring(0, firstSpace))
            .substringBefore('@') // allow "/sms@MyBot" in groups
            .lowercase()
        val rest = if (firstSpace < 0) "" else text.substring(firstSpace + 1).trim()

        return when (command) {
            "/sms" -> {
                val sp = rest.indexOfFirst { it.isWhitespace() }
                if (sp < 0) return TelegramCommand.Invalid("格式：/sms <門號> <內容>")
                val number = rest.substring(0, sp).trim()
                val body = rest.substring(sp + 1).trim()
                when {
                    !isPhoneNumber(number) -> TelegramCommand.Invalid("門號格式不正確：$number")
                    body.isEmpty() -> TelegramCommand.Invalid("內容不可為空")
                    else -> TelegramCommand.SendSms(number, body)
                }
            }
            "/reply" -> if (rest.isEmpty()) TelegramCommand.Invalid("格式：/reply <內容>") else TelegramCommand.Reply(rest)
            "/help", "/start" -> TelegramCommand.Help
            "/ping" -> TelegramCommand.Ping
            else -> TelegramCommand.Invalid("未知指令：$command（輸入 /help 查看說明）")
        }
    }

    /** Accepts +, digits and common separators; must hold 6–15 digits. */
    fun isPhoneNumber(value: String): Boolean {
        val digits = value.count { it.isDigit() }
        return digits in 6..15 && value.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
    }
}
