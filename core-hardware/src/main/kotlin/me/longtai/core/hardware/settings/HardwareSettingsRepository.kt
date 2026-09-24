package me.longtai.core.hardware.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.longtai.core.common.print.LabelSize
import me.longtai.core.common.print.PaperWidth
import javax.inject.Inject
import javax.inject.Singleton

data class HardwareSettings(
    val paperWidth: PaperWidth = PaperWidth.MM58,
    val labelSize: LabelSize = LabelSize.DEFAULT,
    /** Use labelLocateAuto() after the printer has learned the label stock. */
    val labelAutoLocate: Boolean = false,
    val cashDrawerEnabled: Boolean = false,
    /** Show printouts on screen instead of printing (development / demo devices). */
    val simulatePrinter: Boolean = false,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /** Treat very fast keyboard input ending with Enter as a barcode scan. */
    val keyboardWedgeEnabled: Boolean = true,
)

private val Context.hardwareDataStore: DataStore<Preferences> by preferencesDataStore(name = "hardware_settings")

@Singleton
class HardwareSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.hardwareDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val PAPER_DOTS = intPreferencesKey("paper_dots")
        val LABEL_WIDTH = intPreferencesKey("label_width_mm")
        val LABEL_HEIGHT = intPreferencesKey("label_height_mm")
        val LABEL_GAP = intPreferencesKey("label_gap_mm")
        val LABEL_AUTO = booleanPreferencesKey("label_auto_locate")
        val CASH_DRAWER = booleanPreferencesKey("cash_drawer")
        val SIMULATE = booleanPreferencesKey("simulate_printer")
        val SOUND = booleanPreferencesKey("sound")
        val VIBRATION = booleanPreferencesKey("vibration")
        val WEDGE = booleanPreferencesKey("keyboard_wedge")
    }

    /** Always holds the latest settings; safe to read synchronously. */
    val settings: StateFlow<HardwareSettings> = store.data
        .map { p -> p.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, HardwareSettings())

    private fun Preferences.toSettings(): HardwareSettings {
        val d = HardwareSettings()
        val label = runCatching {
            LabelSize(
                this[Keys.LABEL_WIDTH] ?: d.labelSize.widthMm,
                this[Keys.LABEL_HEIGHT] ?: d.labelSize.heightMm,
                this[Keys.LABEL_GAP] ?: d.labelSize.gapMm,
            )
        }.getOrDefault(d.labelSize)
        return HardwareSettings(
            paperWidth = this[Keys.PAPER_DOTS]?.let { PaperWidth.fromDots(it) } ?: d.paperWidth,
            labelSize = label,
            labelAutoLocate = this[Keys.LABEL_AUTO] ?: d.labelAutoLocate,
            cashDrawerEnabled = this[Keys.CASH_DRAWER] ?: d.cashDrawerEnabled,
            simulatePrinter = this[Keys.SIMULATE] ?: d.simulatePrinter,
            soundEnabled = this[Keys.SOUND] ?: d.soundEnabled,
            vibrationEnabled = this[Keys.VIBRATION] ?: d.vibrationEnabled,
            keyboardWedgeEnabled = this[Keys.WEDGE] ?: d.keyboardWedgeEnabled,
        )
    }

    suspend fun update(transform: (HardwareSettings) -> HardwareSettings) {
        store.edit { p ->
            val next = transform(p.toSettings())
            p[Keys.PAPER_DOTS] = next.paperWidth.dots
            p[Keys.LABEL_WIDTH] = next.labelSize.widthMm
            p[Keys.LABEL_HEIGHT] = next.labelSize.heightMm
            p[Keys.LABEL_GAP] = next.labelSize.gapMm
            p[Keys.LABEL_AUTO] = next.labelAutoLocate
            p[Keys.CASH_DRAWER] = next.cashDrawerEnabled
            p[Keys.SIMULATE] = next.simulatePrinter
            p[Keys.SOUND] = next.soundEnabled
            p[Keys.VIBRATION] = next.vibrationEnabled
            p[Keys.WEDGE] = next.keyboardWedgeEnabled
        }
    }
}
