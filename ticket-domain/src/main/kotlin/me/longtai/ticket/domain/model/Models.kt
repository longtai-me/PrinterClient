package me.longtai.ticket.domain.model

import me.longtai.core.common.scan.ScanSource

enum class TicketType(val label: String) {
    /** One entry. */
    SINGLE("單次票"),

    /** A fixed number of entries. */
    MULTI("多次票"),

    /** Unlimited entries within the validity period (season pass / access card). */
    PASS("通行證"),
}

enum class TicketStatus(val label: String) {
    ACTIVE("有效"),
    VOID("已作廢"),
}

enum class Direction(val label: String) {
    ENTRY("入場"),
    EXIT("出場"),
}

data class Ticket(
    val id: Long = 0,
    /** Unique ticket code (the barcode/QR identity). */
    val code: String,
    /** Bound NFC card UID, upper-case hex. */
    val nfcUid: String? = null,
    val type: TicketType,
    val holderName: String? = null,
    /** Comma separated zones this ticket admits to; null or "*" admits everywhere. */
    val zone: String? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    /** null = unlimited (PASS). */
    val maxUses: Int? = null,
    val usedCount: Int = 0,
    val status: TicketStatus = TicketStatus.ACTIVE,
    val inside: Boolean = false,
    val lastEntryAt: Long? = null,
    val lastExitAt: Long? = null,
    val createdAt: Long,
    val note: String? = null,
) {
    val remainingUses: Int? get() = maxUses?.let { (it - usedCount).coerceAtLeast(0) }

    fun admitsZone(gateZone: String?): Boolean {
        if (gateZone.isNullOrBlank() || zone.isNullOrBlank()) return true
        val zones = zone.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return zones.isEmpty() || zones.any { it == "*" || it.equals(gateZone.trim(), ignoreCase = true) }
    }

    companion object {
        fun defaultMaxUses(type: TicketType, requested: Int?): Int? = when (type) {
            TicketType.SINGLE -> 1
            TicketType.MULTI -> requested?.coerceAtLeast(1) ?: 1
            TicketType.PASS -> null
        }
    }
}

/** Rules applied at this gate / device. */
data class GatePolicy(
    val gateName: String,
    /** Zone served by this gate; null accepts every ticket regardless of zone. */
    val zone: String? = null,
    /** Minimum minutes between two entries with the same ticket (anti-passback). 0 disables. */
    val antiPassbackMinutes: Int = 0,
    /** Ticket must be scanned out before it can enter again. */
    val requireExitBeforeReentry: Boolean = false,
)

enum class RejectReason(val label: String) {
    NOT_FOUND("查無此票券"),
    VOIDED("票券已作廢"),
    NOT_YET_VALID("尚未到可使用時間"),
    EXPIRED("票券已過期"),
    USED_UP("票券已使用完畢"),
    WRONG_ZONE("非本區可用票券"),
    PASSBACK("短時間內重複入場"),
    ALREADY_INSIDE("已在場內，請先刷出場"),
    NOT_INSIDE("查無入場紀錄"),
    INVALID_SIGNATURE("票券驗證失敗（偽造或金鑰不符）"),
    MALFORMED("無法辨識的票券格式"),
}

data class RedemptionRecord(
    val id: Long = 0,
    val at: Long,
    val code: String,
    val ticketId: Long?,
    val holderName: String?,
    val ticketType: TicketType?,
    val direction: Direction,
    val accepted: Boolean,
    val reason: RejectReason?,
    val gateName: String,
    val operatorName: String,
    val source: ScanSource,
)

data class EventProfile(
    val name: String,
    val subtitle: String? = null,
    val footer: String? = null,
)
