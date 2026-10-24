package me.longtai.ticket.domain.print

import me.longtai.core.common.print.Align
import me.longtai.core.common.print.DividerStyle
import me.longtai.core.common.print.FontSize
import me.longtai.core.common.print.LabelSize
import me.longtai.core.common.print.PrintDocument
import me.longtai.core.common.print.PrintDocumentBuilder
import me.longtai.core.common.print.printDocument
import me.longtai.core.common.time.TimeFormats
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.model.EventProfile
import me.longtai.ticket.domain.model.GatePolicy
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketType
import java.time.LocalDate
import java.time.ZoneId

data class GateStats(
    val accepted: Int,
    val rejected: Int,
    val entries: Int,
    val exits: Int,
    val insideNow: Int,
)

class TicketPrintComposer(private val zone: ZoneId) {

    private fun PrintDocumentBuilder.validity(ticket: Ticket) {
        when {
            ticket.validFrom != null && ticket.validUntil != null -> {
                pair("起", TimeFormats.dateTimeShort(ticket.validFrom, zone))
                pair("迄", TimeFormats.dateTimeShort(ticket.validUntil, zone))
            }
            ticket.validUntil != null -> pair("有效至", TimeFormats.dateTimeShort(ticket.validUntil, zone))
            ticket.validFrom != null -> pair("啟用", TimeFormats.dateTimeShort(ticket.validFrom, zone))
            else -> pair("有效期", "不限")
        }
    }

    private fun usesText(ticket: Ticket): String = when (ticket.type) {
        TicketType.PASS -> "不限次數"
        else -> "${ticket.maxUses ?: 1} 次"
    }

    /** Full ticket on receipt paper with a scannable QR code. */
    fun ticket(ticket: Ticket, qrContent: String, event: EventProfile): PrintDocument = printDocument {
        title(event.name)
        event.subtitle?.let { center(it) }
        divider(DividerStyle.DOUBLE)
        center(ticket.type.label, FontSize.LARGE, bold = true)
        ticket.holderName?.let { pair("持票人", it) }
        ticket.zone?.let { pair("區域", it) }
        pair("可用次數", usesText(ticket))
        validity(ticket)
        feed(8)
        qrCode(qrContent, sizePx = 280)
        center(ticket.code, FontSize.SMALL)
        divider()
        event.footer?.takeIf { it.isNotBlank() }?.let { center(it, FontSize.SMALL) }
        feed(24)
    }

    /** Compact ticket for label stock (wristband / sticker). */
    fun ticketLabel(ticket: Ticket, qrContent: String, event: EventProfile, label: LabelSize): PrintDocument = printDocument {
        text(event.name, Align.LEFT, FontSize.SMALL, bold = true)
        text("${ticket.type.label} ${ticket.holderName.orEmpty()}".trim(), Align.LEFT, FontSize.NORMAL, bold = true)
        ticket.validUntil?.let { text("至 ${TimeFormats.dateTimeShort(it, zone)}", Align.LEFT, FontSize.SMALL) }
        val qr = (minOf(label.widthDots, label.heightDots) - 72).coerceIn(96, 280)
        qrCode(qrContent, sizePx = qr, align = Align.CENTER)
    }

    /** Admission slip printed after a successful scan. */
    fun admissionSlip(ticket: Ticket, direction: Direction, gate: GatePolicy, event: EventProfile, at: Long): PrintDocument = printDocument {
        title(event.name, FontSize.NORMAL)
        center(if (direction == Direction.ENTRY) "入場憑證" else "出場憑證", FontSize.LARGE, bold = true)
        divider()
        pair("時間", TimeFormats.dateTime(at, zone))
        pair("閘口", gate.gateName)
        ticket.holderName?.let { pair("持票人", it) }
        pair("票種", ticket.type.label)
        pair("票號", ticket.code.take(24))
        if (direction == Direction.ENTRY) ticket.remainingUses?.let { pair("剩餘次數", it.toString()) }
        divider()
        event.footer?.takeIf { it.isNotBlank() }?.let { center(it, FontSize.SMALL) }
    }

    fun admissionLabel(ticket: Ticket, direction: Direction, gate: GatePolicy, event: EventProfile, at: Long): PrintDocument = printDocument {
        text(event.name, Align.LEFT, FontSize.SMALL, bold = true)
        text(if (direction == Direction.ENTRY) "已入場" else "已出場", Align.CENTER, FontSize.XLARGE, bold = true)
        text("${TimeFormats.dateTimeShort(at, zone)} ${gate.gateName}", Align.LEFT, FontSize.SMALL)
        ticket.holderName?.let { text(it, Align.LEFT, FontSize.SMALL) }
    }

    fun dailySummary(event: EventProfile, gate: GatePolicy, date: LocalDate, stats: GateStats, printedAt: Long): PrintDocument = printDocument {
        title(event.name, FontSize.NORMAL)
        center("閘口日報", FontSize.LARGE, bold = true)
        pair("日期", TimeFormats.date(date))
        pair("閘口", gate.gateName)
        gate.zone?.let { pair("區域", it) }
        divider()
        pair("通過", stats.accepted.toString())
        pair("拒絕", stats.rejected.toString())
        pair("入場人次", stats.entries.toString())
        pair("出場人次", stats.exits.toString())
        pair("目前場內", stats.insideNow.toString(), bold = true)
        divider()
        text("列印時間 ${TimeFormats.dateTime(printedAt, zone)}", size = FontSize.SMALL)
        feed(24)
    }
}
