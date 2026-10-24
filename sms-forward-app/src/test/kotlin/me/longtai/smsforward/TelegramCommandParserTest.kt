package me.longtai.smsforward

import me.longtai.smsforward.telegram.TelegramCommand
import me.longtai.smsforward.telegram.TelegramCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCommandParserTest {

    @Test
    fun `parses sms command`() {
        val cmd = TelegramCommandParser.parse("/sms 0912345678 你好 世界")
        assertTrue(cmd is TelegramCommand.SendSms)
        cmd as TelegramCommand.SendSms
        assertEquals("0912345678", cmd.number)
        assertEquals("你好 世界", cmd.text)
    }

    @Test
    fun `accepts bot suffix and plus numbers`() {
        val cmd = TelegramCommandParser.parse("/sms@MyBot +886912345678 hi")
        assertTrue(cmd is TelegramCommand.SendSms)
        assertEquals("+886912345678", (cmd as TelegramCommand.SendSms).number)
    }

    @Test
    fun `rejects bad sms`() {
        assertTrue(TelegramCommandParser.parse("/sms 0912") is TelegramCommand.Invalid)
        assertTrue(TelegramCommandParser.parse("/sms abc 內容") is TelegramCommand.Invalid)
    }

    @Test
    fun `parses reply help ping and ignores chat`() {
        assertEquals("哈囉", (TelegramCommandParser.parse("/reply 哈囉") as TelegramCommand.Reply).text)
        assertTrue(TelegramCommandParser.parse("/help") is TelegramCommand.Help)
        assertTrue(TelegramCommandParser.parse("/ping") is TelegramCommand.Ping)
        assertTrue(TelegramCommandParser.parse("just chatting") is TelegramCommand.NotACommand)
        assertTrue(TelegramCommandParser.parse("/unknown") is TelegramCommand.Invalid)
    }
}
