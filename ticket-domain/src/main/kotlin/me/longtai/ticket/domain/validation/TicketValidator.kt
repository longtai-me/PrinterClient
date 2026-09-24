package me.longtai.ticket.domain.validation

import me.longtai.core.common.time.TimeFormats
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.model.GatePolicy
import me.longtai.ticket.domain.model.RejectReason
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketStatus
import java.time.ZoneId

sealed interface Decision {
    val ticket: Ticket?

    /** [ticket] is the updated ticket state that must be persisted. */
    data class Accepted(override val ticket: Ticket, val direction: Direction) : Decision

    data class Rejected(val reason: RejectReason, override val ticket: Ticket?) : Decision
}

object TicketValidator {

    fun validate(ticket: Ticket?, direction: Direction, gate: GatePolicy, now: Long): Decision =
        when (direction) {
            Direction.ENTRY -> validateEntry(ticket, gate, now)
            Direction.EXIT -> validateExit(ticket, gate, now)
        }

    private fun validateEntry(ticket: Ticket?, gate: GatePolicy, now: Long): Decision {
        if (ticket == null) return Decision.Rejected(RejectReason.NOT_FOUND, null)
        val reject = when {
            ticket.status == TicketStatus.VOID -> RejectReason.VOIDED
            ticket.validFrom != null && now < ticket.validFrom -> RejectReason.NOT_YET_VALID
            ticket.validUntil != null && now > ticket.validUntil -> RejectReason.EXPIRED
            !ticket.admitsZone(gate.zone) -> RejectReason.WRONG_ZONE
            ticket.remainingUses == 0 -> RejectReason.USED_UP
            gate.requireExitBeforeReentry && ticket.inside -> RejectReason.ALREADY_INSIDE
            gate.antiPassbackMinutes > 0 && ticket.lastEntryAt != null &&
                now - ticket.lastEntryAt < gate.antiPassbackMinutes * 60_000L -> RejectReason.PASSBACK
            else -> null
        }
        if (reject != null) return Decision.Rejected(reject, ticket)
        return Decision.Accepted(
            ticket.copy(usedCount = ticket.usedCount + 1, inside = true, lastEntryAt = now),
            Direction.ENTRY,
        )
    }

    /** Exits are never blocked by validity or zone: nobody should be trapped inside. */
    private fun validateExit(ticket: Ticket?, gate: GatePolicy, now: Long): Decision {
        if (ticket == null) return Decision.Rejected(RejectReason.NOT_FOUND, null)
        if (gate.requireExitBeforeReentry && !ticket.inside) return Decision.Rejected(RejectReason.NOT_INSIDE, ticket)
        return Decision.Accepted(ticket.copy(inside = false, lastExitAt = now), Direction.EXIT)
    }
}

/** Operator facing texts for a decision. */
object DecisionText {

    data class Text(val title: String, val detail: String?)

    fun describe(decision: Decision, zone: ZoneId): Text = when (decision) {
        is Decision.Accepted -> {
            val t = decision.ticket
            val detail = buildList {
                t.holderName?.let { add(it) }
                add(t.type.label)
                if (decision.direction == Direction.ENTRY) {
                    t.remainingUses?.let { add("剩餘 $it 次") }
                }
                t.validUntil?.let { add("有效至 ${TimeFormats.dateTimeShort(it, zone)}") }
            }.joinToString(" · ")
            Text(if (decision.direction == Direction.ENTRY) "驗證通過，請入場" else "出場登記完成", detail)
        }
        is Decision.Rejected -> {
            val t = decision.ticket
            val detail = when (decision.reason) {
                RejectReason.NOT_YET_VALID -> t?.validFrom?.let { "開始時間 ${TimeFormats.dateTimeShort(it, zone)}" }
                RejectReason.EXPIRED -> t?.validUntil?.let { "到期時間 ${TimeFormats.dateTimeShort(it, zone)}" }
                RejectReason.USED_UP, RejectReason.PASSBACK, RejectReason.ALREADY_INSIDE ->
                    t?.lastEntryAt?.let { "上次入場 ${TimeFormats.dateTimeShort(it, zone)}" }
                RejectReason.WRONG_ZONE -> t?.zone?.let { "票券區域：$it" }
                else -> null
            }
            Text(decision.reason.label, listOfNotNull(t?.holderName, detail).joinToString(" · ").ifEmpty { null })
        }
    }
}
