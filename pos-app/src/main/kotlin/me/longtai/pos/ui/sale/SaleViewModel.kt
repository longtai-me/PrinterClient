package me.longtai.pos.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.longtai.core.auth.Session
import me.longtai.core.common.money.Money
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.common.scan.ScanSource
import me.longtai.core.hardware.feedback.Feedback
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.hardware.printer.PrinterGateway
import me.longtai.core.hardware.printer.PrinterState
import me.longtai.pos.data.CartHolder
import me.longtai.pos.data.MemberRepository
import me.longtai.pos.data.PosSettings
import me.longtai.pos.data.PosSettingsRepository
import me.longtai.pos.data.ProductRepository
import me.longtai.pos.data.SalesRepository
import me.longtai.pos.data.ValidationException
import me.longtai.pos.domain.cart.Cart
import me.longtai.pos.domain.cart.CartTotals
import me.longtai.pos.domain.cart.PriceCalculator
import me.longtai.pos.domain.model.Discount
import me.longtai.pos.domain.model.Member
import me.longtai.pos.domain.model.Product
import me.longtai.pos.domain.model.Shift
import javax.inject.Inject

sealed interface ShiftState {
    data object Loading : ShiftState
    data object Closed : ShiftState
    data class Open(val shift: Shift) : ShiftState
}

sealed interface SaleEvent {
    data class Message(val text: String) : SaleEvent
    data class UnknownCode(val code: String) : SaleEvent
    data class UnknownCard(val uid: String) : SaleEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SaleViewModel @Inject constructor(
    private val cartHolder: CartHolder,
    private val products: ProductRepository,
    private val members: MemberRepository,
    private val sales: SalesRepository,
    settingsRepository: PosSettingsRepository,
    private val session: Session,
    private val feedback: Feedback,
    printer: PrinterGateway,
) : ViewModel() {

    val cart: StateFlow<Cart> = cartHolder.cart
    val settings: StateFlow<PosSettings> = settingsRepository.settings
    val printerState: StateFlow<PrinterState> = printer.state

    val totals: StateFlow<CartTotals> = combine(cart, settings) { c, s -> PriceCalculator.calculate(c, s.tax) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CartTotals.EMPTY)

    val shift: StateFlow<ShiftState> = sales.openShift
        .map { if (it == null) ShiftState.Closed else ShiftState.Open(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShiftState.Loading)

    val lowStockCount: StateFlow<Int> = products.lowStockCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val searchResults: StateFlow<List<Product>> = _query
        .debounce(200)
        .flatMapLatest { q -> if (q.isBlank()) flowOf(emptyList()) else products.search(q, includeInactive = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<SaleEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SaleEvent> = _events.asSharedFlow()

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** Enter on the search field: exact barcode/SKU adds the product, otherwise keep the result list. */
    fun submitQuery() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            val product = products.findByCode(q)
            if (product != null) {
                add(product)
                _query.value = ""
            } else if (searchResults.value.size == 1) {
                add(searchResults.value.first())
                _query.value = ""
            }
        }
    }

    fun onScan(event: ScanEvent) {
        // Keyboard-wedge scanners also type into the focused search field.
        if (event.source == ScanSource.KEYBOARD || _query.value.trim() == event.code) _query.value = ""
        handleCode(event.code)
    }

    private fun handleCode(code: String) {
        viewModelScope.launch {
            val product = products.findByCode(code)
            if (product != null) {
                add(product)
                return@launch
            }
            val member = members.findByCode(code)
            if (member != null) {
                attachMember(member)
                return@launch
            }
            feedback.error()
            _events.emit(SaleEvent.UnknownCode(code))
        }
    }

    fun onNfc(tag: NfcTag) {
        viewModelScope.launch {
            val member = members.findByCard(tag.uid)
            if (member != null) {
                attachMember(member)
            } else {
                // A tag may carry a member number or product code as NDEF text.
                val text = tag.ndefTexts.firstOrNull()
                if (text != null && members.findByCode(text) != null) {
                    attachMember(members.findByCode(text)!!)
                } else {
                    feedback.error()
                    _events.emit(SaleEvent.UnknownCard(tag.uid))
                }
            }
        }
    }

    fun add(product: Product, quantity: Int = 1) {
        cartHolder.update { it.add(product, quantity) }
        feedback.success()
        product.stockQty?.let { stock ->
            val inCart = cartHolder.cart.value.lines.filter { it.product.id == product.id }.sumOf { it.quantity }
            if (inCart > stock) _events.tryEmit(SaleEvent.Message("「${product.name}」庫存僅剩 $stock"))
        }
    }

    fun increment(lineId: Long) = cartHolder.update { it.increment(lineId) }
    fun decrement(lineId: Long) = cartHolder.update { it.decrement(lineId) }
    fun remove(lineId: Long) = cartHolder.update { it.remove(lineId) }
    fun setQuantity(lineId: Long, quantity: Int) = cartHolder.update { it.setQuantity(lineId, quantity) }
    fun setLineDiscount(lineId: Long, discount: Discount?) = cartHolder.update { it.setLineDiscount(lineId, discount) }
    fun setOrderDiscount(discount: Discount?) = cartHolder.update { it.withOrderDiscount(discount) }
    fun clearCart() = cartHolder.clear()

    fun attachMember(member: Member) {
        cartHolder.update { it.withMember(member) }
        feedback.success()
        _events.tryEmit(SaleEvent.Message("會員：${member.name}"))
    }

    fun detachMember() = cartHolder.update { it.withMember(null) }

    fun lookupMember(code: String) {
        viewModelScope.launch {
            val member = members.findByCode(code) ?: members.findByCard(code)
            if (member != null) attachMember(member) else _events.emit(SaleEvent.Message("找不到會員「$code」"))
        }
    }

    fun openShift(openingCash: Money) {
        val operator = session.current.value ?: return
        viewModelScope.launch {
            try {
                sales.openShift(operator.id, operator.name, openingCash)
                _events.emit(SaleEvent.Message("已開班"))
            } catch (e: ValidationException) {
                _events.emit(SaleEvent.Message(e.message ?: "開班失敗"))
            }
        }
    }
}
