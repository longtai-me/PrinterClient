package me.longtai.ticket.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.longtai.core.auth.Session
import me.longtai.core.common.codec.Hex
import me.longtai.core.common.scan.ScanSource
import me.longtai.core.common.time.TimeFormats
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.hardware.printer.PrintMode
import me.longtai.core.hardware.printer.PrinterGateway
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.hardware.settings.HardwareSettingsRepository
import me.longtai.ticket.data.db.RedemptionDao
import me.longtai.ticket.data.db.RedemptionEntity
import me.longtai.ticket.data.db.TicketDao
import me.longtai.ticket.data.db.TicketDatabase
import me.longtai.ticket.data.db.TicketEntity
import me.longtai.ticket.domain.codec.TicketClaims
import me.longtai.ticket.domain.codec.TicketCodes
import me.longtai.ticket.domain.io.RedemptionCsv
import me.longtai.ticket.domain.io.TicketCsv
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.model.RedemptionRecord
import me.longtai.ticket.domain.model.RejectReason
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketStatus
import me.longtai.ticket.domain.model.TicketType
import me.longtai.ticket.domain.print.GateStats
import me.longtai.ticket.domain.print.TicketPrintComposer
import me.longtai.ticket.domain.validation.Decision
import me.longtai.ticket.domain.validation.RedemptionEngine
import me.longtai.ticket.domain.validation.RedemptionOutcome
import me.longtai.ticket.domain.validation.TicketStore
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

class TicketException(message: String) : IllegalArgumentException(message)

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() }

fun TicketEntity.toModel() = Ticket(
    id = id, code = code, nfcUid = nfcUid, type = enumOrNull<TicketType>(type) ?: TicketType.SINGLE, holderName = holderName, zone = zone,
    validFrom = validFrom, validUntil = validUntil, maxUses = maxUses, usedCount = usedCount,
    status = enumOrNull<TicketStatus>(status) ?: TicketStatus.ACTIVE, inside = inside, lastEntryAt = lastEntryAt,
    lastExitAt = lastExitAt, createdAt = createdAt, note = note,
)

fun Ticket.toEntity() = TicketEntity(
    id = id, code = code, nfcUid = nfcUid, type = type.name, holderName = holderName, zone = zone, validFrom = validFrom,
    validUntil = validUntil, maxUses = maxUses, usedCount = usedCount, status = status.name, inside = inside,
    lastEntryAt = lastEntryAt, lastExitAt = lastExitAt, createdAt = createdAt, note = note,
)

fun RedemptionEntity.toModel() = RedemptionRecord(
    id = id, at = at, code = code, ticketId = ticketId, holderName = holderName, ticketType = enumOrNull<TicketType>(ticketType),
    direction = enumOrNull<Direction>(direction) ?: Direction.ENTRY, accepted = accepted, reason = enumOrNull<RejectReason>(reason),
    gateName = gateName, operatorName = operatorName, source = enumOrNull<ScanSource>(source) ?: ScanSource.MANUAL,
)

enum class TicketFilter(val label: String) { ALL("全部"), ACTIVE("可使用"), USED("已用完"), VOID("已作廢"), INSIDE("場內") }

enum class LogFilter(val label: String) { ALL("全部"), ACCEPTED("通過"), REJECTED("拒絕") }

@Singleton
class TicketRepository @Inject constructor(
    private val db: TicketDatabase,
    private val tickets: TicketDao,
    private val redemptions: RedemptionDao,
    private val settings: TicketSettingsRepository,
) {
    fun search(query: String, filter: TicketFilter): Flow<List<Ticket>> =
        tickets.search(query.trim(), filter.name, 500).map { list -> list.map { it.toModel() } }

    fun observe(id: Long): Flow<Ticket?> = tickets.observe(id).map { it?.toModel() }

    fun history(ticketId: Long): Flow<List<RedemptionRecord>> = redemptions.observeForTicket(ticketId).map { l -> l.map { it.toModel() } }

    suspend fun get(id: Long): Ticket? = tickets.get(id)?.toModel()

    /** QR content for a ticket: signed when enabled, otherwise the plain code. */
    fun qrContent(ticket: Ticket): String {
        val s = settings.settings.value
        val codec = s.codec()
        return if (s.signedTickets && codec != null) codec.encode(TicketClaims.of(ticket)) else ticket.code
    }

    /** Creates [quantity] tickets with random codes in one transaction. */
    suspend fun issue(template: Ticket, quantity: Int): List<Ticket> {
        if (quantity !in 1..200) throw TicketException("數量需介於 1–200")
        val from = template.validFrom
        val until = template.validUntil
        if (from != null && until != null && until < from) throw TicketException("結束時間不可早於開始時間")
        if (quantity > 1 && template.nfcUid != null) throw TicketException("綁定 NFC 卡時一次只能發行一張")
        template.nfcUid?.let { uid -> tickets.findByNfcUid(uid)?.let { throw TicketException("此卡已綁定票券 ${it.code}") } }
        val now = System.currentTimeMillis()
        return db.withTransaction {
            (1..quantity).map {
                var code: String
                do {
                    code = TicketCodes.generate()
                } while (tickets.findByCode(code) != null)
                val t = template.copy(id = 0, code = code, maxUses = Ticket.defaultMaxUses(template.type, template.maxUses), createdAt = now)
                t.copy(id = tickets.insert(t.toEntity()))
            }
        }
    }

    suspend fun bindCard(ticketId: Long, uid: String?) {
        val normalized = uid?.let { Hex.normalize(it) ?: throw TicketException("卡號格式錯誤") }
        val ticket = tickets.get(ticketId) ?: throw TicketException("找不到票券")
        if (normalized != null) {
            tickets.findByNfcUid(normalized)?.let { if (it.id != ticketId) throw TicketException("此卡已綁定票券 ${it.code}") }
        }
        tickets.update(ticket.copy(nfcUid = normalized))
    }

    suspend fun setStatus(ticketId: Long, status: TicketStatus) {
        val ticket = tickets.get(ticketId) ?: throw TicketException("找不到票券")
        tickets.update(ticket.copy(status = status.name))
    }

    suspend fun resetUsage(ticketId: Long) {
        val ticket = tickets.get(ticketId) ?: throw TicketException("找不到票券")
        tickets.update(ticket.copy(usedCount = 0, inside = false))
    }

    data class ImportSummary(val inserted: Int, val updated: Int, val errors: List<String>)

    /** Upserts by code. Existing tickets keep their usage counters. */
    suspend fun importCsv(text: String): ImportSummary {
        val s = settings.settings.value
        val parsed = TicketCsv.import(text, s.zone, System.currentTimeMillis())
        val errors = parsed.errors.map { "第 ${it.line} 行：${it.message}" }.toMutableList()
        var inserted = 0
        var updated = 0
        db.withTransaction {
            for (t in parsed.tickets) {
                val byUid = t.nfcUid?.let { tickets.findByNfcUid(it) }
                if (byUid != null && byUid.code != t.code) {
                    errors += "${t.code}：NFC 卡已綁定 ${byUid.code}"
                    continue
                }
                val existing = tickets.findByCode(t.code)
                if (existing == null) {
                    tickets.insert(t.toEntity())
                    inserted++
                } else {
                    tickets.update(
                        t.copy(
                            id = existing.id, usedCount = existing.usedCount, inside = existing.inside,
                            lastEntryAt = existing.lastEntryAt, lastExitAt = existing.lastExitAt, createdAt = existing.createdAt,
                        ).toEntity(),
                    )
                    updated++
                }
            }
        }
        return ImportSummary(inserted, updated, errors)
    }

    suspend fun exportCsv(): String = TicketCsv.export(tickets.all().map { it.toModel() }, settings.settings.value.zone)

    fun logs(date: LocalDate, filter: LogFilter): Flow<List<RedemptionRecord>> {
        val range = TimeFormats.dayRange(date, settings.settings.value.zone)
        return redemptions.observeBetween(range.first, range.last + 1, filter.name, 1000).map { l -> l.map { it.toModel() } }
    }

    fun stats(date: LocalDate): Flow<GateStats> {
        val range = TimeFormats.dayRange(date, settings.settings.value.zone)
        return combine(redemptions.observeCounts(range.first, range.last + 1), tickets.observeInsideCount()) { c, inside ->
            GateStats(c.accepted, c.rejected, c.entries, c.exits, inside)
        }
    }

    suspend fun statsOnce(date: LocalDate): GateStats {
        val range = TimeFormats.dayRange(date, settings.settings.value.zone)
        val c = redemptions.counts(range.first, range.last + 1)
        return GateStats(c.accepted, c.rejected, c.entries, c.exits, tickets.insideCount())
    }

    suspend fun exportLogs(date: LocalDate): String {
        val range = TimeFormats.dayRange(date, settings.settings.value.zone)
        return RedemptionCsv.export(redemptions.between(range.first, range.last + 1).map { it.toModel() }, settings.settings.value.zone)
    }
}

data class RedemptionResult(val outcome: RedemptionOutcome, val record: RedemptionRecord)

/**
 * Validates scans against the local ticket database. Scans are processed strictly one at a
 * time and each ticket update plus its log entry is written in a single transaction.
 */
@Singleton
class RedemptionService @Inject constructor(
    private val db: TicketDatabase,
    private val tickets: TicketDao,
    private val redemptions: RedemptionDao,
    private val settings: TicketSettingsRepository,
    private val session: Session,
) {
    private val mutex = Mutex()

    private val store = object : TicketStore {
        override suspend fun findByCode(code: String) = tickets.findByCode(code)?.toModel()
        override suspend fun findByNfcUid(uid: String) = tickets.findByNfcUid(uid)?.toModel()
        override suspend fun insert(ticket: Ticket) = ticket.copy(id = tickets.insert(ticket.toEntity()))
        override suspend fun update(ticket: Ticket) = tickets.update(ticket.toEntity())
    }

    suspend fun redeem(input: String, source: ScanSource, direction: Direction): RedemptionResult = mutex.withLock {
        db.withTransaction {
            val s = settings.settings.value
            val engine = RedemptionEngine(store, s.codec())
            val outcome = engine.redeem(input, source, direction, s.gate, System.currentTimeMillis())
            log(outcome, source, direction)
        }
    }

    /** NFC: the card UID first; if the card is unknown, a ticket code stored on the tag as NDEF text. */
    suspend fun redeemNfc(tag: NfcTag, direction: Direction): RedemptionResult = mutex.withLock {
        db.withTransaction {
            val s = settings.settings.value
            val engine = RedemptionEngine(store, s.codec())
            val now = System.currentTimeMillis()
            var outcome = engine.redeem(tag.uid, ScanSource.NFC, direction, s.gate, now)
            val text = tag.ndefTexts.firstOrNull()
            val notFound = (outcome.decision as? Decision.Rejected)?.reason == RejectReason.NOT_FOUND
            if (notFound && text != null) outcome = engine.redeem(text, ScanSource.SCAN_HEAD, direction, s.gate, now)
            log(outcome, ScanSource.NFC, direction)
        }
    }

    private suspend fun log(outcome: RedemptionOutcome, source: ScanSource, direction: Direction): RedemptionResult {
        val s = settings.settings.value
        val decision = outcome.decision
        val ticket = decision.ticket
        val entity = RedemptionEntity(
            at = System.currentTimeMillis(),
            code = outcome.code.take(128),
            ticketId = ticket?.id?.takeIf { it > 0 },
            holderName = ticket?.holderName,
            ticketType = ticket?.type?.name,
            direction = direction.name,
            accepted = decision is Decision.Accepted,
            reason = (decision as? Decision.Rejected)?.reason?.name,
            gateName = s.gate.gateName,
            operatorName = session.current.value?.name ?: "-",
            source = source.name,
        )
        val id = redemptions.insert(entity)
        return RedemptionResult(outcome, entity.copy(id = id).toModel())
    }
}

/** Printing of tickets, admission slips and daily summaries. */
@Singleton
class TicketPrinter @Inject constructor(
    private val printer: PrinterGateway,
    private val settings: TicketSettingsRepository,
    private val hardware: HardwareSettingsRepository,
    private val repository: TicketRepository,
) {
    private fun composer() = TicketPrintComposer(settings.settings.value.zone)

    private fun mode(target: PrintTarget): PrintMode {
        val hw = hardware.settings.value
        return if (target == PrintTarget.LABEL) PrintMode.Label(hw.labelSize, hw.labelAutoLocate) else PrintMode.Receipt
    }

    suspend fun ticket(ticket: Ticket, target: PrintTarget = settings.settings.value.issueTarget): PrinterResult<Unit> {
        val s = settings.settings.value
        val qr = repository.qrContent(ticket)
        val doc = if (target == PrintTarget.LABEL) {
            composer().ticketLabel(ticket, qr, s.event, hardware.settings.value.labelSize)
        } else {
            composer().ticket(ticket, qr, s.event)
        }
        return printer.print(doc, mode(target))
    }

    suspend fun admission(ticket: Ticket, direction: Direction): PrinterResult<Unit>? {
        val s = settings.settings.value
        val now = System.currentTimeMillis()
        return when (s.printOnAccept) {
            PrintTarget.NONE -> null
            PrintTarget.RECEIPT -> printer.print(composer().admissionSlip(ticket, direction, s.gate, s.event, now))
            PrintTarget.LABEL -> printer.print(composer().admissionLabel(ticket, direction, s.gate, s.event, now), mode(PrintTarget.LABEL))
        }
    }

    suspend fun dailySummary(date: LocalDate, stats: GateStats): PrinterResult<Unit> {
        val s = settings.settings.value
        return printer.print(composer().dailySummary(s.event, s.gate, date, stats, System.currentTimeMillis()))
    }

    /** Prints the signing key as a QR code so another gate device can import it by scanning. */
    suspend fun signingKey(): PrinterResult<Unit> {
        val s = settings.settings.value
        val hex = s.secretHex ?: return PrinterResult.Error(me.longtai.core.common.printer.PrinterResultCodes.SDK_PARAM_ERR)
        val doc = me.longtai.core.common.print.printDocument {
            title("驗票金鑰")
            center("僅供匯入其他驗票機，請勿外流", bold = true)
            center("指紋 ${s.keyFingerprint}")
            qrCode("$KEY_PREFIX$hex", sizePx = 280)
            center("匯入後請立即銷毀此紙本", me.longtai.core.common.print.FontSize.SMALL)
            feed(24)
        }
        return printer.print(doc)
    }

    companion object {
        const val KEY_PREFIX = "TKKEY1:"
    }
}
