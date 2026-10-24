package me.longtai.ticket.domain

import kotlinx.coroutines.runBlocking
import me.longtai.core.common.print.LabelSize
import me.longtai.core.common.print.PlainTextRenderer
import me.longtai.core.common.scan.ScanSource
import me.longtai.core.common.text.DisplayWidth
import me.longtai.ticket.domain.codec.SignedDecode
import me.longtai.ticket.domain.codec.SignedTicketCodec
import me.longtai.ticket.domain.codec.TicketClaims
import me.longtai.ticket.domain.codec.TicketCodes
import me.longtai.ticket.domain.io.TicketCsv
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.model.EventProfile
import me.longtai.ticket.domain.model.GatePolicy
import me.longtai.ticket.domain.model.RejectReason
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketStatus
import me.longtai.ticket.domain.model.TicketType
import me.longtai.ticket.domain.print.TicketPrintComposer
import me.longtai.ticket.domain.validation.Decision
import me.longtai.ticket.domain.validation.DecisionText
import me.longtai.ticket.domain.validation.RedemptionEngine
import me.longtai.ticket.domain.validation.TicketStore
import me.longtai.ticket.domain.validation.TicketValidator
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TicketDomainTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val minute = 60_000L
    private val gate = GatePolicy("A1 東門", zone = "A")

    private fun ticket(type: TicketType = TicketType.SINGLE, maxUses: Int? = 1, zoneName: String? = null) =
        Ticket(id = 1, code = "T1", type = type, maxUses = maxUses, zone = zoneName, createdAt = 0)

    private fun reason(d: Decision) = (d as Decision.Rejected).reason

    @Test
    fun `single ticket can enter once`() {
        val d1 = TicketValidator.validate(ticket(), Direction.ENTRY, gate, 1000)
        assertIs<Decision.Accepted>(d1)
        assertEquals(0, d1.ticket.remainingUses)
        assertTrue(d1.ticket.inside)
        val d2 = TicketValidator.validate(d1.ticket, Direction.ENTRY, gate, 2000)
        assertEquals(RejectReason.USED_UP, reason(d2))
    }

    @Test
    fun `validity window and void status`() {
        val t = ticket().copy(validFrom = 10_000, validUntil = 20_000)
        assertEquals(RejectReason.NOT_YET_VALID, reason(TicketValidator.validate(t, Direction.ENTRY, gate, 9_999)))
        assertEquals(RejectReason.EXPIRED, reason(TicketValidator.validate(t, Direction.ENTRY, gate, 20_001)))
        assertIs<Decision.Accepted>(TicketValidator.validate(t, Direction.ENTRY, gate, 20_000))
        assertEquals(RejectReason.VOIDED, reason(TicketValidator.validate(t.copy(status = TicketStatus.VOID), Direction.ENTRY, gate, 15_000)))
        assertEquals(RejectReason.NOT_FOUND, reason(TicketValidator.validate(null, Direction.ENTRY, gate, 0)))
    }

    @Test
    fun `zone rules`() {
        assertEquals(RejectReason.WRONG_ZONE, reason(TicketValidator.validate(ticket(zoneName = "B"), Direction.ENTRY, gate, 0)))
        assertIs<Decision.Accepted>(TicketValidator.validate(ticket(zoneName = "b, a"), Direction.ENTRY, gate, 0))
        assertIs<Decision.Accepted>(TicketValidator.validate(ticket(zoneName = "*"), Direction.ENTRY, gate, 0))
        assertIs<Decision.Accepted>(TicketValidator.validate(ticket(zoneName = "B"), Direction.ENTRY, gate.copy(zone = null), 0))
    }

    @Test
    fun `anti passback and in out tracking for passes`() {
        val strict = gate.copy(antiPassbackMinutes = 5, requireExitBeforeReentry = true)
        val pass = ticket(TicketType.PASS, null)
        val entered = (TicketValidator.validate(pass, Direction.ENTRY, strict, 0) as Decision.Accepted).ticket
        assertNull(entered.remainingUses)
        assertEquals(RejectReason.ALREADY_INSIDE, reason(TicketValidator.validate(entered, Direction.ENTRY, strict, 10 * minute)))
        val exited = (TicketValidator.validate(entered, Direction.EXIT, strict, 2 * minute) as Decision.Accepted).ticket
        assertEquals(RejectReason.PASSBACK, reason(TicketValidator.validate(exited, Direction.ENTRY, strict, 3 * minute)))
        assertIs<Decision.Accepted>(TicketValidator.validate(exited, Direction.ENTRY, strict, 6 * minute))
        assertEquals(RejectReason.NOT_INSIDE, reason(TicketValidator.validate(exited, Direction.EXIT, strict, 7 * minute)))
    }

    @Test
    fun `exit is allowed for expired or void tickets`() {
        val t = ticket().copy(validUntil = 1, status = TicketStatus.VOID, inside = true)
        assertIs<Decision.Accepted>(TicketValidator.validate(t, Direction.EXIT, gate.copy(requireExitBeforeReentry = true), 100))
    }

    @Test
    fun `multi ticket counts down`() {
        var t = ticket(TicketType.MULTI, 3)
        repeat(3) { t = (TicketValidator.validate(t, Direction.ENTRY, gate, it * 10 * minute) as Decision.Accepted).ticket }
        assertEquals(0, t.remainingUses)
        val text = DecisionText.describe(TicketValidator.validate(t, Direction.ENTRY, gate, 40 * minute), zone)
        assertEquals("票券已使用完畢", text.title)
        assertTrue(text.detail!!.startsWith("上次入場"))
    }

    @Test
    fun `signed codec round trip and tamper detection`() {
        val secret = SignedTicketCodec.generateSecret()
        val codec = SignedTicketCodec(secret)
        val claims = TicketClaims("T8K2M9Q", TicketType.MULTI, "陳|小姐", "A,B", 1_790_000_000_000, 1_790_086_400_000, 5)
        val code = codec.encode(claims)
        assertTrue(code.startsWith("TK1."))
        val decoded = codec.decode(code)
        assertIs<SignedDecode.Valid>(decoded)
        assertEquals(claims.copy(holderName = "陳 小姐"), decoded.claims)

        val tampered = code.replaceRange(6, 7, if (code[6] == 'A') "B" else "A")
        assertNotEquals(code, tampered)
        assertTrue(codec.decode(tampered) is SignedDecode.BadSignature || codec.decode(tampered) is SignedDecode.Malformed)
        assertEquals(SignedDecode.BadSignature, SignedTicketCodec(SignedTicketCodec.generateSecret()).decode(code))
        assertEquals(SignedDecode.NotSigned, codec.decode("T12345"))
        assertEquals(SignedDecode.Malformed, codec.decode("TK1.abc"))
    }

    @Test
    fun `ticket codes are random and manual input is forgiving`() {
        val a = TicketCodes.generate()
        assertEquals(11, a.length)
        assertNotEquals(a, TicketCodes.generate())
        assertEquals(listOf("t0o1", "T0O1", "T001"), TicketCodes.manualCandidates(" t0o1 "))
    }

    private class MemoryStore : TicketStore {
        val tickets = mutableListOf<Ticket>()
        override suspend fun findByCode(code: String) = tickets.firstOrNull { it.code == code }
        override suspend fun findByNfcUid(uid: String) = tickets.firstOrNull { it.nfcUid == uid }
        override suspend fun insert(ticket: Ticket): Ticket = ticket.copy(id = tickets.size + 1L).also { tickets += it }
        override suspend fun update(ticket: Ticket) {
            tickets.replaceAll { if (it.id == ticket.id) ticket else it }
        }
    }

    @Test
    fun `engine enrolls signed tickets once and persists usage`() = runBlocking {
        val store = MemoryStore()
        val codec = SignedTicketCodec(SignedTicketCodec.generateSecret())
        val engine = RedemptionEngine(store, codec)
        val code = codec.encode(TicketClaims("TSIGNED1", TicketType.SINGLE))

        val first = engine.redeem(code, ScanSource.SCAN_HEAD, Direction.ENTRY, gate, 1000)
        assertIs<Decision.Accepted>(first.decision)
        assertTrue(first.enrolled)
        assertEquals("TSIGNED1", first.code)
        assertEquals(1, store.tickets.size)

        val second = engine.redeem(code, ScanSource.CAMERA, Direction.ENTRY, gate, 2000)
        assertEquals(RejectReason.USED_UP, reason(second.decision))
        assertTrue(!second.enrolled)

        val forged = SignedTicketCodec(SignedTicketCodec.generateSecret()).encode(TicketClaims("TFAKE", TicketType.PASS))
        assertEquals(RejectReason.INVALID_SIGNATURE, reason(engine.redeem(forged, ScanSource.SCAN_HEAD, Direction.ENTRY, gate, 3000).decision))
        assertEquals(1, store.tickets.size)
    }

    @Test
    fun `engine resolves nfc uid and manual codes`() = runBlocking {
        val store = MemoryStore()
        store.insert(Ticket(code = "T001", nfcUid = "04A1B2C3", type = TicketType.PASS, createdAt = 0))
        val engine = RedemptionEngine(store, null)
        assertIs<Decision.Accepted>(engine.redeem("04:a1:b2:c3", ScanSource.NFC, Direction.ENTRY, gate, 0).decision)
        assertIs<Decision.Accepted>(engine.redeem("t0o1", ScanSource.MANUAL, Direction.EXIT, gate, 0).decision)
        assertEquals(RejectReason.NOT_FOUND, reason(engine.redeem("t0o1", ScanSource.SCAN_HEAD, Direction.ENTRY, gate, 0).decision))
        assertEquals(RejectReason.INVALID_SIGNATURE, reason(engine.redeem("TK1.xx.yy", ScanSource.SCAN_HEAD, Direction.ENTRY, gate, 0).decision))
    }

    @Test
    fun `csv import validates rows`() {
        val csv = """
            code,type,holder,zone,valid_from,valid_until,max_uses,nfc_uid
            V001,單次,王小明,A,2026-09-24 09:00,2026-09-24,,
            V002,MULTI,,A,,,,
            V003,PASS,林,,,,, 04:11:22:33
            V004,SEASON,,,,,,
            V001,S,,,,,,
            V005,S,,,2026-09-25,2026-09-24,,
        """.trimIndent()
        val r = TicketCsv.import(csv, zone, now = 0)
        assertEquals(listOf("V001", "V003"), r.tickets.map { it.code })
        assertEquals(listOf(3, 5, 6, 7), r.errors.map { it.line })
        val v1 = r.tickets[0]
        assertEquals(1, v1.maxUses)
        assertEquals("2026-09-24 23:59", me.longtai.core.common.time.TimeFormats.dateTimeShort(v1.validUntil!!, zone))
        assertEquals("04112233", r.tickets[1].nfcUid)
        assertNull(r.tickets[1].maxUses)

        val back = TicketCsv.import(TicketCsv.export(r.tickets, zone), zone, 0)
        assertEquals(r.tickets.map { it.code to it.validUntil }, back.tickets.map { it.code to it.validUntil?.let { v -> v - v % 60_000 + 59_999 } })
    }

    @Test
    fun `printouts fit paper`() {
        val composer = TicketPrintComposer(zone)
        val t = Ticket(code = "T8K2M9Q4ZX", type = TicketType.MULTI, maxUses = 5, holderName = "陳小姐", zone = "A", validUntil = 1_790_086_400_000, createdAt = 0)
        val event = EventProfile("2026 秋季音樂節", "台北流行音樂中心", "請妥善保管票券")
        for (doc in listOf(
            composer.ticket(t, "TK1.payload.mac", event),
            composer.ticketLabel(t, "T8K2M9Q4ZX", event, LabelSize.DEFAULT),
            composer.admissionSlip(t, Direction.ENTRY, gate, event, 1_790_000_000_000),
        )) {
            PlainTextRenderer.render(doc, 32).forEach { assertTrue(DisplayWidth.of(it) <= 32, it) }
        }
    }
}
