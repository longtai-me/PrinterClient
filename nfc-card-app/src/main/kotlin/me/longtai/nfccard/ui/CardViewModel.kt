package me.longtai.nfccard.ui

import android.content.Context
import android.nfc.NfcAdapter
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import me.longtai.nfccard.data.CardKind
import me.longtai.nfccard.data.CardRepository
import me.longtai.nfccard.data.NfcCard
import javax.inject.Inject

enum class NfcAvailability { UNSUPPORTED, DISABLED, READY }

@HiltViewModel
class CardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CardRepository,
) : ViewModel() {

    val cards: StateFlow<List<NfcCard>> = repository.cards
    val activeId: StateFlow<Long?> = repository.activeId

    val nfcAvailability: NfcAvailability
        get() {
            val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.UNSUPPORTED
            return if (adapter.isEnabled) NfcAvailability.READY else NfcAvailability.DISABLED
        }

    fun select(id: Long) = repository.select(id)

    fun add(label: String, kind: CardKind, payload: String) = repository.add(label, kind, payload)

    fun update(card: NfcCard) = repository.update(card)

    fun delete(id: Long) = repository.delete(id)
}
