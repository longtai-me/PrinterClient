package me.longtai.ticket.domain.io

import me.longtai.core.common.codec.Hex
import me.longtai.core.common.csv.Csv
import me.longtai.core.common.csv.CsvTable
import me.longtai.core.common.time.TimeFormats
import me.longtai.ticket.domain.model.RedemptionRecord
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketStatus
import me.longtai.ticket.domain.model.TicketType
import java.time.ZoneId

data class ImportError(val line: Int, val message: String)

data class TicketImportResult(val tickets: List<Ticket>, val errors: List<ImportError>)

object TicketCsv {
    val HEADER = listOf(
        "code", "type", "holder", "zone", "valid_from", "valid_until", "max_uses", "nfc_uid", "status", "used", "note",
    )
    private val REQUIRED = listOf("code", "type")

    fun parseType(value: String?): TicketType? = when (value?.trim()?.uppercase()) {
        "SINGLE", "S", "單次", "單次票" -> TicketType.SINGLE
        "MULTI", "M", "多次", "多次票" -> TicketType.MULTI
        "PASS", "P", "通行證", "期間票" -> TicketType.PASS
        else -> null
    }

    fun export(tickets: List<Ticket>, zone: ZoneId): String = Csv.write(
        listOf(HEADER) + tickets.map { t ->
            listOf(
                t.code, t.type.name, t.holderName.orEmpty(), t.zone.orEmpty(),
                t.validFrom?.let { TimeFormats.dateTimeShort(it, zone) }.orEmpty(),
                t.validUntil?.let { TimeFormats.dateTimeShort(it, zone) }.orEmpty(),
                t.maxUses?.toString().orEmpty(), t.nfcUid.orEmpty(), t.status.name, t.usedCount.toString(), t.note.orEmpty(),
            )
        },
    )

    /**
     * Imports tickets. Dates accept "yyyy-MM-dd HH:mm" or "yyyy-MM-dd"; a date-only
     * valid_until means the end of that day. Usage counters are not imported.
     */
    fun import(text: String, zone: ZoneId, now: Long): TicketImportResult {
        val table = try {
            CsvTable(Csv.parse(text))
        } catch (e: Csv.ParseException) {
            return TicketImportResult(emptyList(), listOf(ImportError(e.line, "CSV 格式錯誤")))
        }
        val missing = table.missingColumns(REQUIRED)
        if (missing.isNotEmpty()) {
            return TicketImportResult(emptyList(), listOf(ImportError(1, "缺少欄位: ${missing.joinToString()}")))
        }
        val tickets = mutableListOf<Ticket>()
        val errors = mutableListOf<ImportError>()
        val seenCodes = mutableSetOf<String>()
        val seenUids = mutableSetOf<String>()
        for (r in table.records) {
            val problems = mutableListOf<String>()
            val code = r["code"]
            val type = parseType(r["type"])
            val fromText = r["valid_from"]
            val untilText = r["valid_until"]
            val from = fromText?.let { TimeFormats.parseDateTime(it, zone) }
            val until = untilText?.let { TimeFormats.parseDateTime(it, zone, endOfDay = true) }
            val maxUsesText = r["max_uses"]
            val maxUses = maxUsesText?.toIntOrNull()
            val uidText = r["nfc_uid"]
            val uid = uidText?.let { Hex.normalize(it) }
            val status = when (r["status"]?.uppercase()) {
                null, "ACTIVE", "有效" -> TicketStatus.ACTIVE
                "VOID", "作廢", "已作廢" -> TicketStatus.VOID
                else -> null
            }

            if (code == null) problems += "缺少票券代碼"
            else if (code.length > 128) problems += "票券代碼過長"
            else if (!seenCodes.add(code)) problems += "代碼重複: $code"
            if (type == null) problems += "票種無效（SINGLE/MULTI/PASS）"
            if (fromText != null && from == null) problems += "valid_from 日期格式錯誤"
            if (untilText != null && until == null) problems += "valid_until 日期格式錯誤"
            if (from != null && until != null && until < from) problems += "結束時間早於開始時間"
            if (maxUsesText != null && (maxUses == null || maxUses < 1)) problems += "max_uses 必須是正整數"
            if (type == TicketType.MULTI && maxUses == null) problems += "多次票需要 max_uses"
            if (uidText != null && uid == null) problems += "nfc_uid 必須是十六進位"
            else if (uid != null && !seenUids.add(uid)) problems += "NFC 卡號重複: $uid"
            if (status == null) problems += "status 值無效"

            if (problems.isNotEmpty()) {
                errors += ImportError(r.lineNumber, problems.joinToString("；"))
                continue
            }
            tickets += Ticket(
                code = code!!,
                nfcUid = uid,
                type = type!!,
                holderName = r["holder"],
                zone = r["zone"],
                validFrom = from,
                validUntil = until,
                maxUses = Ticket.defaultMaxUses(type, maxUses),
                status = status!!,
                createdAt = now,
                note = r["note"],
            )
        }
        return TicketImportResult(tickets, errors)
    }
}

object RedemptionCsv {
    val HEADER = listOf("time", "code", "holder", "type", "direction", "result", "reason", "gate", "operator", "source")

    fun export(records: List<RedemptionRecord>, zone: ZoneId): String = Csv.write(
        listOf(HEADER) + records.map { r ->
            listOf(
                TimeFormats.dateTime(r.at, zone), r.code, r.holderName.orEmpty(), r.ticketType?.name.orEmpty(),
                r.direction.name, if (r.accepted) "ACCEPTED" else "REJECTED", r.reason?.label.orEmpty(),
                r.gateName, r.operatorName, r.source.name,
            )
        },
    )
}
