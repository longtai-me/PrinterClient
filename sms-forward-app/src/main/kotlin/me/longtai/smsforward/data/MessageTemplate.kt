package me.longtai.smsforward.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders the forwarded message from an owner-defined template. Placeholders let the owner put a
 * custom prefix or suffix around the SMS body, e.g. "來自 {sender} {time}\n{body}\n—我的手機".
 */
object MessageTemplate {

    const val DEFAULT = "[轉發] {sender} {time}\n{body}"

    /** Placeholders the UI offers as insertable chips. */
    val VARIABLES = listOf("{sender}", "{number}", "{body}", "{time}", "{date}", "{datetime}")

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun render(
        template: String,
        sender: String,
        body: String,
        timestampMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val at = Instant.ofEpochMilli(timestampMs).atZone(zone)
        // If the template has no body placeholder, keep the body so nothing is silently dropped.
        val base = template.ifBlank { "{body}" }.let { if (it.contains("{body}")) it else "$it\n{body}" }
        return base
            .replace("{sender}", sender)
            .replace("{number}", sender)
            .replace("{time}", TIME.format(at))
            .replace("{date}", DATE.format(at))
            .replace("{datetime}", DATETIME.format(at))
            .replace("{body}", body)
    }
}
