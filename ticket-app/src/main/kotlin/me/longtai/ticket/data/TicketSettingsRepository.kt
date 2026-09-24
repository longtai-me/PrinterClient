package me.longtai.ticket.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.core.common.codec.Hex
import me.longtai.core.common.security.Hmac
import me.longtai.ticket.domain.codec.SignedTicketCodec
import me.longtai.ticket.domain.model.Direction
import me.longtai.ticket.domain.model.EventProfile
import me.longtai.ticket.domain.model.GatePolicy
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class PrintTarget(val label: String) {
    NONE("不列印"),
    RECEIPT("收據紙"),
    LABEL("標籤紙"),
}

data class TicketSettings(
    val event: EventProfile = EventProfile(name = "活動名稱", footer = "請妥善保管票券，遺失恕不補發"),
    val gate: GatePolicy = GatePolicy(gateName = "閘口 1"),
    val defaultDirection: Direction = Direction.ENTRY,
    /** Seconds the result stays on screen before returning to "ready". 0 keeps it until the next scan. */
    val resultSeconds: Int = 3,
    val printOnAccept: PrintTarget = PrintTarget.NONE,
    val issueTarget: PrintTarget = PrintTarget.RECEIPT,
    /** Issue tickets as HMAC-signed QR codes (verifiable offline on every gate sharing the key). */
    val signedTickets: Boolean = true,
    val secretHex: String? = null,
) {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun codec(): SignedTicketCodec? = secretHex?.let { Hex.decode(it) }?.takeIf { it.size >= 16 }?.let { SignedTicketCodec(it) }

    /** Short fingerprint so operators can confirm two gates share the same key. */
    val keyFingerprint: String?
        get() = secretHex?.let { Hex.decode(it) }?.let { Hex.encode(Hmac.sha256(it, "fingerprint".toByteArray()).copyOf(4), " ") }
}

private val Context.ticketDataStore: DataStore<Preferences> by preferencesDataStore(name = "ticket_settings")

@Singleton
class TicketSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.ticketDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val EVENT_NAME = stringPreferencesKey("event_name")
        val EVENT_SUBTITLE = stringPreferencesKey("event_subtitle")
        val EVENT_FOOTER = stringPreferencesKey("event_footer")
        val GATE_NAME = stringPreferencesKey("gate_name")
        val GATE_ZONE = stringPreferencesKey("gate_zone")
        val ANTI_PASSBACK = intPreferencesKey("anti_passback_minutes")
        val REQUIRE_EXIT = booleanPreferencesKey("require_exit")
        val DIRECTION = stringPreferencesKey("default_direction")
        val RESULT_SECONDS = intPreferencesKey("result_seconds")
        val PRINT_ON_ACCEPT = stringPreferencesKey("print_on_accept")
        val ISSUE_TARGET = stringPreferencesKey("issue_target")
        val SIGNED = booleanPreferencesKey("signed_tickets")
        val SECRET = stringPreferencesKey("signing_secret")
    }

    val settings: StateFlow<TicketSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, TicketSettings())

    init {
        // Every installation gets its own signing key until an administrator imports a shared one.
        scope.launch {
            if (store.data.first()[Keys.SECRET] == null) {
                store.edit { if (it[Keys.SECRET] == null) it[Keys.SECRET] = Hex.encode(SignedTicketCodec.generateSecret()) }
            }
        }
    }

    private fun Preferences.toSettings(): TicketSettings {
        val d = TicketSettings()
        return TicketSettings(
            event = EventProfile(
                name = this[Keys.EVENT_NAME] ?: d.event.name,
                subtitle = this[Keys.EVENT_SUBTITLE] ?: d.event.subtitle,
                footer = this[Keys.EVENT_FOOTER] ?: d.event.footer,
            ),
            gate = GatePolicy(
                gateName = this[Keys.GATE_NAME] ?: d.gate.gateName,
                zone = this[Keys.GATE_ZONE],
                antiPassbackMinutes = this[Keys.ANTI_PASSBACK] ?: d.gate.antiPassbackMinutes,
                requireExitBeforeReentry = this[Keys.REQUIRE_EXIT] ?: d.gate.requireExitBeforeReentry,
            ),
            defaultDirection = enumOr(this[Keys.DIRECTION], d.defaultDirection),
            resultSeconds = this[Keys.RESULT_SECONDS] ?: d.resultSeconds,
            printOnAccept = enumOr(this[Keys.PRINT_ON_ACCEPT], d.printOnAccept),
            issueTarget = enumOr(this[Keys.ISSUE_TARGET], d.issueTarget),
            signedTickets = this[Keys.SIGNED] ?: d.signedTickets,
            secretHex = this[Keys.SECRET],
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    suspend fun update(transform: (TicketSettings) -> TicketSettings) {
        store.edit { p ->
            val s = transform(p.toSettings())
            fun put(key: Preferences.Key<String>, value: String?) {
                if (value.isNullOrBlank()) p.remove(key) else p[key] = value.trim()
            }
            p[Keys.EVENT_NAME] = s.event.name.ifBlank { "活動名稱" }.trim()
            put(Keys.EVENT_SUBTITLE, s.event.subtitle)
            put(Keys.EVENT_FOOTER, s.event.footer)
            p[Keys.GATE_NAME] = s.gate.gateName.ifBlank { "閘口 1" }.trim()
            put(Keys.GATE_ZONE, s.gate.zone)
            p[Keys.ANTI_PASSBACK] = s.gate.antiPassbackMinutes.coerceIn(0, 24 * 60)
            p[Keys.REQUIRE_EXIT] = s.gate.requireExitBeforeReentry
            p[Keys.DIRECTION] = s.defaultDirection.name
            p[Keys.RESULT_SECONDS] = s.resultSeconds.coerceIn(0, 30)
            p[Keys.PRINT_ON_ACCEPT] = s.printOnAccept.name
            p[Keys.ISSUE_TARGET] = s.issueTarget.name
            p[Keys.SIGNED] = s.signedTickets
            s.secretHex?.let { p[Keys.SECRET] = it }
        }
    }
}
