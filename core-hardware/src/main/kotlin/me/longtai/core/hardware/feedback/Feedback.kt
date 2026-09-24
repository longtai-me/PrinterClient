package me.longtai.core.hardware.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import me.longtai.core.hardware.settings.HardwareSettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Audible and haptic confirmation, essential when the operator is not looking at the screen. */
@Singleton
class Feedback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: HardwareSettingsRepository,
) {
    private val tone: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: RuntimeException) {
            Timber.w(e, "ToneGenerator unavailable")
            null
        }
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun success() = play(ToneGenerator.TONE_PROP_ACK, 150, longArrayOf(0, 40))

    fun error() = play(ToneGenerator.TONE_SUP_ERROR, 500, longArrayOf(0, 150, 100, 150))

    fun warning() = play(ToneGenerator.TONE_PROP_NACK, 250, longArrayOf(0, 80))

    fun click() = play(ToneGenerator.TONE_PROP_BEEP, 60, null)

    private fun play(toneType: Int, durationMs: Int, pattern: LongArray?) {
        val s = settings.settings.value
        if (s.soundEnabled) runCatching { tone?.startTone(toneType, durationMs) }
        if (s.vibrationEnabled && pattern != null) vibrate(pattern)
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }
}
