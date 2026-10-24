package me.longtai.pos.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.longtai.core.auth.Session
import me.longtai.core.hardware.printer.PrintMode
import me.longtai.core.hardware.printer.PrinterGateway
import me.longtai.core.hardware.printer.PrinterResult
import me.longtai.core.hardware.settings.HardwareSettingsRepository
import me.longtai.pos.domain.cart.Cart
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.Product
import me.longtai.pos.domain.model.Shift
import me.longtai.pos.domain.print.ReceiptComposer
import me.longtai.pos.domain.report.CashReconciliation
import me.longtai.pos.domain.report.SalesSummary
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** The sale in progress, shared by the register and payment screens. Cleared on logout. */
@Singleton
class CartHolder @Inject constructor(session: Session) {
    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            session.current.collect { if (it == null) clear() }
        }
    }

    fun update(transform: (Cart) -> Cart) = _cart.update(transform)

    fun clear() = _cart.update { it.clear() }
}

/** Builds and prints receipts, reports and labels using the store settings. */
@Singleton
class PosPrinter @Inject constructor(
    private val printer: PrinterGateway,
    private val settings: PosSettingsRepository,
    private val hardware: HardwareSettingsRepository,
) {
    private fun composer(): ReceiptComposer {
        val s = settings.settings.value
        return ReceiptComposer(s.money, s.zone)
    }

    suspend fun receipt(order: Order, reprint: Boolean = false): PrinterResult<Unit> {
        val s = settings.settings.value
        return printer.print(composer().receipt(order, s.store, reprint), PrintMode.Receipt, if (reprint) 1 else s.receiptCopies)
    }

    suspend fun refundSlip(order: Order): PrinterResult<Unit> =
        printer.print(composer().refundSlip(order, settings.settings.value.store))

    suspend fun shiftReport(shift: Shift, summary: SalesSummary, cash: CashReconciliation, closing: Boolean): PrinterResult<Unit> =
        printer.print(composer().shiftReport(settings.settings.value.store, shift, summary, cash, closing, System.currentTimeMillis()))

    suspend fun dailyReport(date: LocalDate, summary: SalesSummary): PrinterResult<Unit> =
        printer.print(composer().dailyReport(settings.settings.value.store, date, summary, System.currentTimeMillis()))

    suspend fun priceLabel(product: Product, copies: Int): PrinterResult<Unit> {
        val hw = hardware.settings.value
        val doc = composer().priceLabel(product, hw.labelSize, settings.settings.value.store.name)
        return printer.print(doc, PrintMode.Label(hw.labelSize, hw.labelAutoLocate), copies)
    }

    /** Opens the cash drawer when enabled in the device settings; null when disabled. */
    suspend fun openDrawerIfEnabled(): PrinterResult<Unit>? =
        if (hardware.settings.value.cashDrawerEnabled) printer.openCashDrawer() else null
}
