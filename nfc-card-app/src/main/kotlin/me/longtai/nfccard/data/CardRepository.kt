package me.longtai.nfccard.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.longtai.nfccard.hce.NdefEmu
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class CardKind(val label: String) {
    MEMBER("會員卡號"),
    TICKET("票券代碼"),
    TEXT("純文字"),
}

data class NfcCard(
    val id: Long,
    val label: String,
    val kind: CardKind,
    /** The value emitted as an NDEF Text record when this card is tapped. */
    val payload: String,
)

/**
 * Stores the emulated cards and which one is active. Backed by SharedPreferences so the HCE
 * service can read the active card's bytes synchronously, even right after the process starts.
 */
@Singleton
class CardRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nfc_cards", Context.MODE_PRIVATE)

    private val _cards = MutableStateFlow<List<NfcCard>>(emptyList())
    val cards: StateFlow<List<NfcCard>> = _cards.asStateFlow()

    private val _activeId = MutableStateFlow<Long?>(null)
    val activeId: StateFlow<Long?> = _activeId.asStateFlow()

    /** The Type 4 NDEF file for the active card; read directly by the HCE service. */
    @Volatile
    var activeFile: ByteArray = NdefEmu.EMPTY_FILE
        private set

    init {
        val list = load()
        _cards.value = list.ifEmpty { defaults() }
        if (list.isEmpty()) persist()
        _activeId.value = prefs.getLong(KEY_ACTIVE, -1).takeIf { it >= 0 } ?: _cards.value.firstOrNull()?.id
        rebuildActive()
    }

    fun add(label: String, kind: CardKind, payload: String): NfcCard {
        val card = NfcCard(id = System.currentTimeMillis(), label = label.trim(), kind = kind, payload = payload.trim())
        _cards.value = _cards.value + card
        if (_activeId.value == null) _activeId.value = card.id
        persist()
        rebuildActive()
        return card
    }

    fun update(card: NfcCard) {
        _cards.value = _cards.value.map { if (it.id == card.id) card else it }
        persist()
        rebuildActive()
    }

    fun delete(id: Long) {
        _cards.value = _cards.value.filterNot { it.id == id }
        if (_activeId.value == id) _activeId.value = _cards.value.firstOrNull()?.id
        persist()
        rebuildActive()
    }

    fun select(id: Long) {
        _activeId.value = id
        persist()
        rebuildActive()
    }

    fun activeCard(): NfcCard? = _cards.value.firstOrNull { it.id == _activeId.value }

    private fun rebuildActive() {
        activeFile = activeCard()?.let { NdefEmu.textFile(it.payload) } ?: NdefEmu.EMPTY_FILE
    }

    private fun load(): List<NfcCard> {
        val raw = prefs.getString(KEY_CARDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NfcCard(
                    id = o.getLong("id"),
                    label = o.getString("label"),
                    kind = runCatching { CardKind.valueOf(o.getString("kind")) }.getOrDefault(CardKind.TEXT),
                    payload = o.getString("payload"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val arr = JSONArray()
        _cards.value.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("kind", c.kind.name)
                    .put("payload", c.payload),
            )
        }
        prefs.edit()
            .putString(KEY_CARDS, arr.toString())
            .putLong(KEY_ACTIVE, _activeId.value ?: -1)
            .apply()
    }

    private fun defaults(): List<NfcCard> = listOf(
        NfcCard(1, "會員 M0001", CardKind.MEMBER, "M0001"),
        NfcCard(2, "測試票券", CardKind.TICKET, "T-DEMO-0001"),
    )

    private companion object {
        const val KEY_CARDS = "cards"
        const val KEY_ACTIVE = "active_id"
    }
}
