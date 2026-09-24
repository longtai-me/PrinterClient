package me.longtai.pos.domain.checkout

import me.longtai.core.common.money.Money
import me.longtai.pos.domain.model.Payment
import me.longtai.pos.domain.model.PaymentMethod

sealed class TenderError(val message: String) {
    data object InvalidAmount : TenderError("金額必須大於 0")
    data object ExceedsRemaining : TenderError("非現金付款不可超過應付餘額")
    data object AlreadyPaid : TenderError("此筆交易已付清")
}

/**
 * Split-tender payment state. Cash may be over-tendered (change is returned);
 * card and other electronic methods must not exceed the remaining balance.
 */
data class Tender(val total: Money, val payments: List<Payment> = emptyList()) {

    val paid: Money get() = Money.sum(payments.map { it.amount })
    val remaining: Money get() = (total - paid).coerceAtLeast(Money.ZERO)
    val change: Money get() = Money.sum(payments.map { it.change })
    val isComplete: Boolean get() = paid >= total

    fun add(method: PaymentMethod, tendered: Money, reference: String? = null): Result {
        if (isComplete) return Result.Failure(TenderError.AlreadyPaid)
        if (!tendered.isPositive) return Result.Failure(TenderError.InvalidAmount)
        val payment = if (method == PaymentMethod.CASH) {
            Payment(method, amount = tendered.coerceAtMost(remaining), tendered = tendered, reference = reference)
        } else {
            if (tendered > remaining) return Result.Failure(TenderError.ExceedsRemaining)
            Payment(method, amount = tendered, tendered = tendered, reference = reference?.trim()?.ifEmpty { null })
        }
        return Result.Success(copy(payments = payments + payment))
    }

    fun removeAt(index: Int): Tender =
        if (index in payments.indices) copy(payments = payments.filterIndexed { i, _ -> i != index }) else this

    sealed interface Result {
        data class Success(val tender: Tender) : Result
        data class Failure(val error: TenderError) : Result
    }

    companion object {
        /**
         * Quick-cash buttons: the exact amount, then the amount rounded up to each
         * banknote denomination, e.g. 368 → [368, 400, 500, 1000].
         */
        fun cashSuggestions(remaining: Money, denominations: List<Money>, limit: Int = 4): List<Money> {
            if (!remaining.isPositive) return emptyList()
            val rounded = denominations.filter { it.isPositive }.map { d ->
                val units = (remaining.minor + d.minor - 1) / d.minor
                Money(units * d.minor)
            }
            return (listOf(remaining) + rounded).distinct().sorted().take(limit)
        }
    }
}
