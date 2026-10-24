package me.longtai.smsforward.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
import javax.inject.Inject
import javax.inject.Singleton

data class ForwardSettings(
    /** Master switch. Forwarding only happens while this is true. Off by default. */
    val enabled: Boolean = false,
    /** Forward each matching SMS as a text message to [targetNumber]. */
    val smsChannelEnabled: Boolean = true,
    /** Forward each matching SMS to a Telegram chat via a bot. */
    val telegramChannelEnabled: Boolean = false,
    /** Destination phone number for the SMS channel. */
    val targetNumber: String = "",
    /** Telegram bot token (from @BotFather), used by the Telegram channel. */
    val telegramBotToken: String = "",
    /** Telegram chat id the bot sends to / takes commands from (your own private chat with the bot). */
    val telegramChatId: String = "",
    /** Owner-defined message template with {sender}/{body}/{time}/... placeholders. */
    val template: String = MessageTemplate.DEFAULT,
    /** Accept commands sent to the bot (from [telegramChatId] only) to send SMS from this device. */
    val telegramCommandsEnabled: Boolean = false,
    /** Comma-separated keywords; when set, only messages containing one are forwarded. Empty = forward all. */
    val keywordFilter: String = "",
) {
    val keywords: List<String>
        get() = keywordFilter.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    /** Token + chat id present, regardless of whether the outbound channel toggle is on. */
    val telegramConfigured: Boolean get() = telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()

    val smsReady: Boolean get() = smsChannelEnabled && targetNumber.isNotBlank()
    val telegramReady: Boolean get() = telegramChannelEnabled && telegramConfigured
    val anyChannelReady: Boolean get() = smsReady || telegramReady

    /** The command listener runs when enabled and the bot is configured. */
    val commandListenerActive: Boolean get() = telegramCommandsEnabled && telegramConfigured

    /** Short description of the active destinations, shown in the ongoing notification. */
    fun statusSummary(): String {
        val parts = buildList {
            if (smsReady) add("簡訊→$targetNumber")
            if (telegramReady) add("Telegram")
        }
        return if (parts.isEmpty()) "尚未設定轉發方式" else parts.joinToString("、")
    }
}

private val Context.forwardDataStore: DataStore<Preferences> by preferencesDataStore(name = "sms_forward_settings")

@Singleton
class ForwardSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) {
    private val store = context.forwardDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val SMS_CHANNEL = booleanPreferencesKey("sms_channel")
        val TG_CHANNEL = booleanPreferencesKey("telegram_channel")
        val TARGET = stringPreferencesKey("target_number")
        val TG_TOKEN = stringPreferencesKey("telegram_token")
        val TG_CHAT = stringPreferencesKey("telegram_chat_id")
        val TEMPLATE = stringPreferencesKey("message_template")
        val TG_COMMANDS = booleanPreferencesKey("telegram_commands")
        val INCLUDE_SENDER = booleanPreferencesKey("include_sender") // legacy, read for migration only
        val KEYWORDS = stringPreferencesKey("keyword_filter")

        // Runtime state (not shown in the UI)
        val LAST_UPDATE_ID = longPreferencesKey("tg_last_update_id")
        val LAST_INBOUND_SENDER = stringPreferencesKey("last_inbound_sender")
    }

    val settings: StateFlow<ForwardSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, ForwardSettings())

    private fun Preferences.toSettings(): ForwardSettings {
        val d = ForwardSettings()
        // Migrate the old include-sender toggle into a template the first time.
        val migratedTemplate = this[Keys.TEMPLATE] ?: when (this[Keys.INCLUDE_SENDER]) {
            false -> "{body}"
            else -> MessageTemplate.DEFAULT
        }
        return ForwardSettings(
            enabled = this[Keys.ENABLED] ?: d.enabled,
            smsChannelEnabled = this[Keys.SMS_CHANNEL] ?: d.smsChannelEnabled,
            telegramChannelEnabled = this[Keys.TG_CHANNEL] ?: d.telegramChannelEnabled,
            targetNumber = this[Keys.TARGET] ?: d.targetNumber,
            telegramBotToken = this[Keys.TG_TOKEN]?.let { cipher.reveal(it) } ?: d.telegramBotToken,
            telegramChatId = this[Keys.TG_CHAT] ?: d.telegramChatId,
            template = migratedTemplate,
            telegramCommandsEnabled = this[Keys.TG_COMMANDS] ?: d.telegramCommandsEnabled,
            keywordFilter = this[Keys.KEYWORDS] ?: d.keywordFilter,
        )
    }

    /** Reads the latest persisted settings directly (used by the receiver and service, which have no UI state). */
    suspend fun current(): ForwardSettings = store.data.first().toSettings()

    suspend fun update(transform: (ForwardSettings) -> ForwardSettings) {
        store.edit { p ->
            val s = transform(p.toSettings())
            p[Keys.ENABLED] = s.enabled
            p[Keys.SMS_CHANNEL] = s.smsChannelEnabled
            p[Keys.TG_CHANNEL] = s.telegramChannelEnabled
            p[Keys.TARGET] = s.targetNumber.trim()
            p[Keys.TG_TOKEN] = cipher.protect(s.telegramBotToken.trim())
            p[Keys.TG_CHAT] = s.telegramChatId.trim()
            p[Keys.TEMPLATE] = s.template
            p[Keys.TG_COMMANDS] = s.telegramCommandsEnabled
            p[Keys.KEYWORDS] = s.keywordFilter.trim()
        }
    }

    // --- Runtime state for the command poller ---

    suspend fun lastUpdateId(): Long = store.data.first()[Keys.LAST_UPDATE_ID] ?: 0L

    suspend fun setLastUpdateId(value: Long) {
        store.edit { it[Keys.LAST_UPDATE_ID] = value }
    }

    suspend fun lastInboundSender(): String? = store.data.first()[Keys.LAST_INBOUND_SENDER]?.takeIf { it.isNotBlank() }

    suspend fun setLastInboundSender(sender: String) {
        store.edit { it[Keys.LAST_INBOUND_SENDER] = sender }
    }
}
