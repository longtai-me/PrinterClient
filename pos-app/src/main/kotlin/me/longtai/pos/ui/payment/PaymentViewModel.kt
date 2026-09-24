package me.longtai.pos.ui.payment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.longtai.core.auth.Session
import me.longtai.core.common.money.Money
import me.longtai.core.hardware.feedback.Feedback
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.pos.data.CartHolder
import me.longtai.pos.data.PosPrinter
import me.longtai.pos.data.PosSettings
import me.longtai.pos.data.PosSettingsRepository
import me.longtai.pos.data.SalesRepository
import me.longtai.pos.domain.cart.Cart
import me.longtai.pos.domain.cart.CartTotals
import me.longtai.pos.domain.cart.PriceCalculator
import me.longtai.pos.domain.checkout.Cashier
import me.longtai.pos.domain.checkout.CheckoutException
import me.longtai.pos.domain.checkout.OrderFactory
import me.longtai.pos.domain.checkout.Tender
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.PaymentMethod
import timber.log.Timber
import javax.inject.Inject

data class PaymentUiState(
    val tender: Tender,
    val method: PaymentMethod = PaymentMethod.CASH,
    val amountText: String = "",
    val reference: String = "",
    val busy: Boolean = false,
    val completed: Order? = null,
)

@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val cartHolder: CartHolder,
    settingsRepository: PosSettingsRepository,
    private val sales: SalesRepository,
    private val session: Session,
    private val printer: PosPrinter,
    private val feedback: Feedback,
) : ViewModel() {

    val settings: StateFlow<PosSettings> = settingsRepository.settings

    /** The cart is frozen when payment starts so the amount due cannot change underneath the tender. */
    private val cart: Cart = cartHolder.cart.value
    val totals: CartTotals = PriceCalculator.calculate(cart, settings.value.tax)

    private val _state = MutableStateFlow(PaymentUiState(Tender(totals.total)))
    val state: StateFlow<PaymentUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val isEmpty: Boolean get() = cart.isEmpty

    fun selectMethod(method: PaymentMethod) = _state.update { it.copy(method = method, amountText = "", reference = "") }

    fun setAmountText(text: String) = _state.update { it.copy(amountText = text) }

    fun setReference(text: String) = _state.update { it.copy(reference = text.take(40)) }

    fun cashSuggestions(): List<Money> = Tender.cashSuggestions(_state.value.tender.remaining, settings.value.cashDenominations)

    /** Adds a payment; an empty amount means "the remaining balance". */
    fun addPayment(amount: Money? = null) {
        val s = _state.value
        if (s.busy || s.completed != null) return
        val money = settings.value.money
        val value = amount ?: if (s.amountText.isBlank()) s.tender.remaining else money.parse(s.amountText)
        if (value == null) {
            _messages.tryEmit("金額格式錯誤")
            return
        }
        when (val result = s.tender.add(s.method, value, s.reference)) {
            is Tender.Result.Failure -> {
                feedback.error()
                _messages.tryEmit(result.error.message)
            }
            is Tender.Result.Success -> {
                _state.update { it.copy(tender = result.tender, amountText = "", reference = "") }
                if (result.tender.isComplete) complete()
            }
        }
    }

    fun removePayment(index: Int) = _state.update { if (it.completed == null) it.copy(tender = it.tender.removeAt(index)) else it }

    /** Completes a sale once fully paid (also used for zero-total sales). */
    fun complete() {
        val s = _state.value
        if (s.busy || s.completed != null || !s.tender.isComplete || cart.isEmpty) return
        val operator = session.current.value ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val shift = sales.currentShift() ?: throw CheckoutException("尚未開班，無法結帳")
                val cfg = settings.value
                val order = sales.saveSale { orderNo ->
                    OrderFactory.create(
                        cart = cart,
                        totals = totals,
                        tender = s.tender,
                        tax = cfg.tax,
                        orderNo = orderNo,
                        createdAt = System.currentTimeMillis(),
                        cashier = Cashier(operator.id, operator.name),
                        shiftId = shift.id,
                        loyalty = cfg.loyalty,
                    )
                }
                cartHolder.clear()
                feedback.success()
                _state.update { it.copy(busy = false, completed = order) }
                if (order.payments.any { it.method == PaymentMethod.CASH }) {
                    (printer.openDrawerIfEnabled() as? PrinterResult.Error)?.let { _messages.emit("錢箱：${it.message}") }
                }
                if (cfg.autoPrintReceipt) print(order, reprint = false)
            } catch (e: CheckoutException) {
                _state.update { it.copy(busy = false) }
                _messages.emit(e.message ?: "結帳失敗")
            } catch (e: Exception) {
                Timber.e(e, "checkout failed")
                _state.update { it.copy(busy = false) }
                _messages.emit("結帳失敗，交易未儲存：${e.message}")
            }
        }
    }

    fun reprint() {
        val order = _state.value.completed ?: return
        viewModelScope.launch { print(order, reprint = true) }
    }

    private suspend fun print(order: Order, reprint: Boolean) {
        when (val r = printer.receipt(order, reprint)) {
            is PrinterResult.Ok -> _messages.emit(if (reprint) "已補印收據" else "收據列印中")
            is PrinterResult.Error -> _messages.emit("收據未列印：${r.message}")
        }
    }
}
