package me.longtai.core.common.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object TimeFormats {
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE_TIME_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun dateTime(epochMs: Long, zone: ZoneId): String = DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun dateTimeShort(epochMs: Long, zone: ZoneId): String = DATE_TIME_SHORT.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun date(epochMs: Long, zone: ZoneId): String = DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun date(date: LocalDate): String = DATE.format(date)
    fun time(epochMs: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun compactDate(date: LocalDate): String = COMPACT_DATE.format(date)

    fun localDate(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** [start, end) epoch millis of the given local day. */
    fun dayRange(date: LocalDate, zone: ZoneId): LongRange {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until end
    }

    /**
     * Parses "yyyy-MM-dd HH:mm[:ss]" or "yyyy-MM-dd" (start of day, or end of day when
     * [endOfDay] is set) in the given zone. Also accepts 'T' as the date/time separator
     * and '/' as the date separator.
     */
    fun parseDateTime(text: String, zone: ZoneId, endOfDay: Boolean = false): Long? {
        val s = text.trim().replace('/', '-').replace('T', ' ')
        if (s.isEmpty()) return null
        return try {
            when (s.length) {
                10 -> {
                    val d = LocalDate.parse(s, DATE)
                    if (endOfDay) d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    else d.atStartOfDay(zone).toInstant().toEpochMilli()
                }
                16 -> LocalDateTime.parse(s, DATE_TIME_SHORT).atZone(zone).toInstant().toEpochMilli()
                19 -> LocalDateTime.parse(s, DATE_TIME).atZone(zone).toInstant().toEpochMilli()
                else -> null
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
