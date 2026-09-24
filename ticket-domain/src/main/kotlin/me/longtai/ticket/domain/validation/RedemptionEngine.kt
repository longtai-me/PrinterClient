package me.longtai.ticket.domain.validation

import me.longtai.core.common.codec.Hex
import me.longtai.core.common.scan.ScanSource
import me.longtai.ticket.domain.codec.SignedDecode
import me.longtai.ticket.domain.codec.SignedTicketCodec
import me.longtai.ticket.domain.codec.TicketCodes
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.model.GatePolicy
import me.longtai.ticket.domain.model.RejectReason
import me.longtai.ticket.domain.model.Ticket

/** Persistence port implemented by the app's database layer. */
interface TicketStore {
    suspend fun findByCode(code: String): Ticket?
    suspend fun findByNfcUid(uid: String): Ticket?

    /** Inserts and returns the ticket with its generated id. */
    suspend fun insert(ticket: Ticket): Ticket
    suspend fun update(ticket: Ticket)
}

data class RedemptionOutcome(
    val decision: Decision,
    /** Code used for logging: ticket code when resolved, otherwise the raw input. */
    val code: String,
    /** A valid signed ticket seen for the first time on this device. */
    val enrolled: Boolean,
)

/**
 * Resolves scanned input to a ticket, validates it against the gate policy and persists
 * the new ticket state. Callers must serialise calls (one scan at a time) so that two
 * quick scans of the same ticket cannot both consume the last use.
 */
class RedemptionEngine(
    private val store: TicketStore,
    private val codec: SignedTicketCodec?,
) {

    suspend fun redeem(input: String, source: ScanSource, direction: Direction, gate: GatePolicy, now: Long): RedemptionOutcome {
        val raw = input.trim()
        if (raw.isEmpty()) return RedemptionOutcome(Decision.Rejected(RejectReason.MALFORMED, null), raw, false)

        if (source == ScanSource.NFC) {
            val uid = Hex.normalize(raw) ?: raw
            return finish(store.findByNfcUid(uid), uid, direction, gate, now, enrolled = false)
        }

        if (SignedTicketCodec.isSigned(raw)) {
            val decoded = codec?.decode(raw) ?: SignedDecode.BadSignature
            return when (decoded) {
                is SignedDecode.Valid -> {
                    val existing = store.findByCode(decoded.claims.code)
                    val ticket = existing ?: store.insert(decoded.claims.toTicket(now))
                    finish(ticket, ticket.code, direction, gate, now, enrolled = existing == null)
                }
                SignedDecode.BadSignature -> RedemptionOutcome(Decision.Rejected(RejectReason.INVALID_SIGNATURE, null), raw.take(64), false)
                SignedDecode.Malformed, SignedDecode.NotSigned -> RedemptionOutcome(Decision.Rejected(RejectReason.MALFORMED, null), raw.take(64), false)
            }
        }

        val candidates = if (source == ScanSource.MANUAL) TicketCodes.manualCandidates(raw) else listOf(raw)
        for (candidate in candidates) {
            val ticket = store.findByCode(candidate)
            if (ticket != null) return finish(ticket, ticket.code, direction, gate, now, enrolled = false)
        }
        return RedemptionOutcome(Decision.Rejected(RejectReason.NOT_FOUND, null), raw, false)
    }

    private suspend fun finish(
        ticket: Ticket?,
        code: String,
        direction: Direction,
        gate: GatePolicy,
        now: Long,
        enrolled: Boolean,
    ): RedemptionOutcome {
        val decision = TicketValidator.validate(ticket, direction, gate, now)
        if (decision is Decision.Accepted) store.update(decision.ticket)
        return RedemptionOutcome(decision, code, enrolled)
    }
}
