package me.longtai.smsforward.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    /** Forwarding only happens while this is true. Off by default. */
    val enabled: Boolean = false,
    /** Destination phone number every matching SMS is forwarded to. */
    val targetNumber: String = "",
    /** Prefix each forward with the original sender and time. */
    val includeSenderInfo: Boolean = true,
    /** Comma-separated keywords; when set, only messages containing one are forwarded. Empty = forward all. */
    val keywordFilter: String = "",
) {
    val keywords: List<String>
        get() = keywordFilter.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    val isConfigured: Boolean get() = targetNumber.isNotBlank()
}

private val Context.forwardDataStore: DataStore<Preferences> by preferencesDataStore(name = "sms_forward_settings")

@Singleton
class ForwardSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.forwardDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val TARGET = stringPreferencesKey("target_number")
        val INCLUDE_SENDER = booleanPreferencesKey("include_sender")
        val KEYWORDS = stringPreferencesKey("keyword_filter")
    }

    val settings: StateFlow<ForwardSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, ForwardSettings())

    private fun Preferences.toSettings(): ForwardSettings {
        val d = ForwardSettings()
        return ForwardSettings(
            enabled = this[Keys.ENABLED] ?: d.enabled,
            targetNumber = this[Keys.TARGET] ?: d.targetNumber,
            includeSenderInfo = this[Keys.INCLUDE_SENDER] ?: d.includeSenderInfo,
            keywordFilter = this[Keys.KEYWORDS] ?: d.keywordFilter,
        )
    }

    /** Reads the latest persisted settings directly (used by the SMS receiver, which has no UI state). */
    suspend fun current(): ForwardSettings = store.data.first().toSettings()

    suspend fun update(transform: (ForwardSettings) -> ForwardSettings) {
        store.edit { p ->
            val s = transform(p.toSettings())
            p[Keys.ENABLED] = s.enabled
            p[Keys.TARGET] = s.targetNumber.trim()
            p[Keys.INCLUDE_SENDER] = s.includeSenderInfo
            p[Keys.KEYWORDS] = s.keywordFilter.trim()
        }
    }
}
