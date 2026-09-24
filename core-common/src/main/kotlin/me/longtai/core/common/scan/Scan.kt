package me.longtai.core.common.scan

enum class ScanSource {
    /** Built-in infrared/laser scan head (QSC), delivered by broadcast. */
    SCAN_HEAD,

    /** Camera scanner app. */
    CAMERA,

    /** Scanner configured as a keyboard (HID "wedge") device. */
    KEYBOARD,

    /** NFC card or tag UID. */
    NFC,

    /** Code typed in by the operator. */
    MANUAL,
}

data class ScanEvent(val code: String, val source: ScanSource, val timestampMs: Long)

/**
 * Reassembles barcodes from scanners that emulate a keyboard. Scanners type much faster
 * than people, so characters arriving further apart than [maxInterKeyDelayMs] reset the
 * buffer; the terminating Enter emits the buffered code.
 */
class KeyboardWedgeDecoder(
    private val maxInterKeyDelayMs: Long = 60,
    private val minLength: Int = 3,
) {
    private val buffer = StringBuilder()
    private var lastKeyAtMs = Long.MIN_VALUE

    fun onChar(c: Char, timestampMs: Long) {
        if (buffer.isNotEmpty() && timestampMs - lastKeyAtMs > maxInterKeyDelayMs) buffer.setLength(0)
        if (!c.isISOControl()) buffer.append(c)
        lastKeyAtMs = timestampMs
    }

    /** Returns the scanned code, or null when the buffered input looks like human typing. */
    fun onEnter(timestampMs: Long): String? {
        val fast = buffer.isNotEmpty() && timestampMs - lastKeyAtMs <= maxInterKeyDelayMs
        val code = buffer.toString()
        reset()
        return if (fast && code.length >= minLength) code else null
    }

    val isCollecting get() = buffer.isNotEmpty()

    fun reset() {
        buffer.setLength(0)
        lastKeyAtMs = Long.MIN_VALUE
    }
}

/** Drops repeated reads of the same code within [windowMs] (scan heads often double-fire). */
class ScanDebouncer(private val windowMs: Long = 800) {
    private var lastCode: String? = null
    private var lastAtMs: Long = Long.MIN_VALUE

    fun accept(code: String, timestampMs: Long): Boolean {
        val duplicate = code == lastCode && timestampMs - lastAtMs < windowMs
        lastCode = code
        lastAtMs = timestampMs
        return !duplicate
    }
}
