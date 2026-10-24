package me.longtai.smsforward

import me.longtai.smsforward.data.MessageTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MessageTemplateTest {
    private val zone = ZoneId.of("Asia/Taipei")
    // 2026-01-02 03:04:05 Asia/Taipei
    private val ts = java.time.ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `renders placeholders`() {
        val out = MessageTemplate.render("來自 {sender} {datetime}\n{body}", "0912345678", "測試", ts, zone)
        assertEquals("來自 0912345678 2026-01-02 03:04:05\n測試", out)
    }

    @Test
    fun `body is substituted last so sms content cannot inject variables`() {
        val out = MessageTemplate.render("{time} {body}", "0912", "點 {time} 到 {sender}", ts, zone)
        // The {time}/{sender} inside the body stay literal.
        assertEquals("03:04 點 {time} 到 {sender}", out)
    }

    @Test
    fun `template without body keeps the body`() {
        val out = MessageTemplate.render("前綴", "0912", "內容", ts, zone)
        assertTrue(out.contains("內容"))
        assertEquals("前綴\n內容", out)
    }

    @Test
    fun `blank template falls back to body`() {
        assertEquals("內容", MessageTemplate.render("", "0912", "內容", ts, zone))
    }
}
