package me.longtai.ticket.domain.codec

import me.longtai.core.common.codec.Base64Url
import me.longtai.core.common.security.Hmac
import me.longtai.core.common.security.SecureRandomBytes
import me.longtai.ticket.domain.model.Ticket
import me.longtai.ticket.domain.model.TicketType

/** The data carried inside a signed QR ticket. */
data class TicketClaims(
    val code: String,
    val type: TicketType,
    val holderName: String? = null,
    val zone: String? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val maxUses: Int? = null,
) {
    fun toTicket(createdAt: Long) = Ticket(
        code = code,
        type = type,
        holderName = holderName,
        zone = zone,
        validFrom = validFrom,
        validUntil = validUntil,
        maxUses = Ticket.defaultMaxUses(type, maxUses),
        createdAt = createdAt,
    )

    companion object {
        fun of(ticket: Ticket) = TicketClaims(
            code = ticket.code,
            type = ticket.type,
            holderName = ticket.holderName,
            zone = ticket.zone,
            validFrom = ticket.validFrom,
            validUntil = ticket.validUntil,
            maxUses = ticket.maxUses,
        )
    }
}

sealed interface SignedDecode {
    /** Plain code; look it up in the local ticket database. */
    data object NotSigned : SignedDecode
    data object BadSignature : SignedDecode
    data object Malformed : SignedDecode
    data class Valid(val claims: TicketClaims) : SignedDecode
}

/**
 * Offline-verifiable ticket codes: `TK1.<payload>.<mac>` where payload is Base64Url of
 * the pipe separated claims and mac is the first 16 bytes of HMAC-SHA256(secret, "TK1.<payload>").
 *
 * Every gate device sharing the secret can admit tickets issued elsewhere without a
 * network connection; usage counts are then tracked per device.
 */
class SignedTicketCodec(private val secret: ByteArray) {

    init {
        require(secret.size >= 16) { "secret must be at least 128 bits" }
    }

    fun encode(claims: TicketClaims): String {
        val fields = listOf(
            claims.code,
            TYPE_CODES.getValue(claims.type),
            claims.zone.orEmpty(),
            claims.validFrom?.div(1000)?.toString().orEmpty(),
            claims.validUntil?.div(1000)?.toString().orEmpty(),
            claims.maxUses?.toString().orEmpty(),
            claims.holderName.orEmpty(),
        ).map { it.replace(SEPARATOR, " ") }
        val payload = Base64Url.encode(fields.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8))
        val signed = "$PREFIX$payload"
        return "$signed.${Base64Url.encode(mac(signed))}"
    }

    fun decode(code: String): SignedDecode {
        val text = code.trim()
        if (!text.startsWith(PREFIX)) return SignedDecode.NotSigned
        val lastDot = text.lastIndexOf('.')
        if (lastDot <= PREFIX.length) return SignedDecode.Malformed
        val signed = text.substring(0, lastDot)
        val providedMac = Base64Url.decode(text.substring(lastDot + 1)) ?: return SignedDecode.Malformed
        if (!Hmac.equalsConstantTime(mac(signed), providedMac)) return SignedDecode.BadSignature
        val payloadBytes = Base64Url.decode(signed.substring(PREFIX.length)) ?: return SignedDecode.Malformed
        val fields = String(payloadBytes, Charsets.UTF_8).split(SEPARATOR)
        if (fields.size != 7) return SignedDecode.Malformed
        val type = TYPE_CODES.entries.firstOrNull { it.value == fields[1] }?.key ?: return SignedDecode.Malformed
        if (fields[0].isBlank()) return SignedDecode.Malformed
        fun epochMs(s: String): Long? = if (s.isEmpty()) null else s.toLongOrNull()?.times(1000)
        val claims = TicketClaims(
            code = fields[0],
            type = type,
            zone = fields[2].ifEmpty { null },
            validFrom = epochMs(fields[3]),
            validUntil = epochMs(fields[4]),
            maxUses = fields[5].ifEmpty { null }?.toIntOrNull(),
            holderName = fields[6].ifEmpty { null },
        )
        if ((fields[3].isNotEmpty() && claims.validFrom == null) || (fields[4].isNotEmpty() && claims.validUntil == null)) {
            return SignedDecode.Malformed
        }
        return SignedDecode.Valid(claims)
    }

    private fun mac(data: String): ByteArray = Hmac.sha256(secret, data.toByteArray(Charsets.UTF_8)).copyOf(16)

    companion object {
        const val PREFIX = "TK1."
        private const val SEPARATOR = "|"
        private val TYPE_CODES = mapOf(TicketType.SINGLE to "S", TicketType.MULTI to "M", TicketType.PASS to "P")

        fun generateSecret(): ByteArray = SecureRandomBytes.next(32)

        fun isSigned(code: String) = code.trim().startsWith(PREFIX)
    }
}

/** Random, unguessable ticket codes using Crockford Base32 (no I, L, O, U). */
object TicketCodes {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun generate(prefix: String = "T", length: Int = 10): String {
        val bytes = SecureRandomBytes.next(length)
        return prefix + String(CharArray(length) { ALPHABET[(bytes[it].toInt() and 0xFF) % 32] })
    }

    /**
     * Lookup candidates for a typed-in code: as entered, upper-cased, and with Crockford
     * look-alikes (O→0, I/L→1) corrected. Signed codes are returned unchanged.
     */
    fun manualCandidates(input: String): List<String> {
        val s = input.trim()
        if (s.isEmpty()) return emptyList()
        if (SignedTicketCodec.isSigned(s)) return listOf(s)
        val upper = s.uppercase()
        return listOf(s, upper, upper.replace('O', '0').replace('I', '1').replace('L', '1')).distinct()
    }
}
