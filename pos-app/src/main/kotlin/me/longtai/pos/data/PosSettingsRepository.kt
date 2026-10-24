package me.longtai.pos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.longtai.core.common.money.Money
import me.longtai.core.common.money.MoneyFormat
import me.longtai.pos.domain.checkout.OrderNumbers
import me.longtai.pos.domain.model.LoyaltyRule
import me.longtai.pos.domain.model.StoreProfile
import me.longtai.pos.domain.model.TaxConfig
import me.longtai.pos.domain.model.TaxMode
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class PosSettings(
    val store: StoreProfile = StoreProfile(name = "我的商店", footer = "謝謝光臨，歡迎再度光臨"),
    val currencySymbol: String = "$",
    val currencyDecimals: Int = 0,
    val tax: TaxConfig = TaxConfig(TaxMode.INCLUSIVE, 500),
    /** Spend this many major currency units to earn one point; 0 disables points. */
    val pointsPerAmount: Long = 100,
    val deviceCode: String = "01",
    val autoPrintReceipt: Boolean = true,
    val receiptCopies: Int = 1,
) {
    val money: MoneyFormat get() = MoneyFormat(currencyDecimals, currencySymbol)
    val loyalty: LoyaltyRule get() = LoyaltyRule(if (pointsPerAmount > 0) money.ofMajor(pointsPerAmount) else Money.ZERO)
    val zone: ZoneId get() = ZoneId.systemDefault()

    /** Banknotes offered as quick-cash buttons. */
    val cashDenominations: List<Money>
        get() = (if (currencyDecimals == 0) listOf(100L, 500L, 1000L) else listOf(10L, 20L, 50L, 100L)).map { money.ofMajor(it) }
}

private val Context.posDataStore: DataStore<Preferences> by preferencesDataStore(name = "pos_settings")

@Singleton
class PosSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.posDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val STORE_NAME = stringPreferencesKey("store_name")
        val STORE_ADDRESS = stringPreferencesKey("store_address")
        val STORE_PHONE = stringPreferencesKey("store_phone")
        val STORE_TAX_ID = stringPreferencesKey("store_tax_id")
        val STORE_FOOTER = stringPreferencesKey("store_footer")
        val CURRENCY_SYMBOL = stringPreferencesKey("currency_symbol")
        val CURRENCY_DECIMALS = intPreferencesKey("currency_decimals")
        val TAX_MODE = stringPreferencesKey("tax_mode")
        val TAX_RATE = intPreferencesKey("tax_rate_bp")
        val POINTS_PER_AMOUNT = longPreferencesKey("points_per_amount")
        val DEVICE_CODE = stringPreferencesKey("device_code")
        val AUTO_PRINT = booleanPreferencesKey("auto_print")
        val COPIES = intPreferencesKey("receipt_copies")
    }

    val settings: StateFlow<PosSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, PosSettings())

    private fun Preferences.toSettings(): PosSettings {
        val d = PosSettings()
        return PosSettings(
            store = StoreProfile(
                name = this[Keys.STORE_NAME] ?: d.store.name,
                address = this[Keys.STORE_ADDRESS] ?: d.store.address,
                phone = this[Keys.STORE_PHONE] ?: d.store.phone,
                taxId = this[Keys.STORE_TAX_ID] ?: d.store.taxId,
                footer = this[Keys.STORE_FOOTER] ?: d.store.footer,
            ),
            currencySymbol = this[Keys.CURRENCY_SYMBOL] ?: d.currencySymbol,
            currencyDecimals = (this[Keys.CURRENCY_DECIMALS] ?: d.currencyDecimals).coerceIn(0, 2),
            tax = runCatching {
                TaxConfig(TaxMode.valueOf(this[Keys.TAX_MODE] ?: d.tax.mode.name), this[Keys.TAX_RATE] ?: d.tax.rateBp)
            }.getOrDefault(d.tax),
            pointsPerAmount = this[Keys.POINTS_PER_AMOUNT] ?: d.pointsPerAmount,
            deviceCode = this[Keys.DEVICE_CODE]?.takeIf { OrderNumbers.isValidDeviceCode(it) } ?: d.deviceCode,
            autoPrintReceipt = this[Keys.AUTO_PRINT] ?: d.autoPrintReceipt,
            receiptCopies = (this[Keys.COPIES] ?: d.receiptCopies).coerceIn(1, 3),
        )
    }

    suspend fun update(transform: (PosSettings) -> PosSettings) {
        store.edit { p ->
            val s = transform(p.toSettings())
            fun put(key: Preferences.Key<String>, value: String?) {
                if (value.isNullOrBlank()) p.remove(key) else p[key] = value.trim()
            }
            p[Keys.STORE_NAME] = s.store.name.ifBlank { "我的商店" }.trim()
            put(Keys.STORE_ADDRESS, s.store.address)
            put(Keys.STORE_PHONE, s.store.phone)
            put(Keys.STORE_TAX_ID, s.store.taxId)
            put(Keys.STORE_FOOTER, s.store.footer)
            p[Keys.CURRENCY_SYMBOL] = s.currencySymbol
            p[Keys.CURRENCY_DECIMALS] = s.currencyDecimals.coerceIn(0, 2)
            p[Keys.TAX_MODE] = s.tax.mode.name
            p[Keys.TAX_RATE] = s.tax.rateBp
            p[Keys.POINTS_PER_AMOUNT] = s.pointsPerAmount.coerceAtLeast(0)
            p[Keys.DEVICE_CODE] = s.deviceCode
            p[Keys.AUTO_PRINT] = s.autoPrintReceipt
            p[Keys.COPIES] = s.receiptCopies.coerceIn(1, 3)
        }
    }
}
