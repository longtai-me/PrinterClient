package me.longtai.core.common.security

import me.longtai.core.common.codec.Hex
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hmac {
    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Constant-time comparison to avoid timing side channels. */
    fun equalsConstantTime(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/**
 * Salted PBKDF2-HMAC-SHA256 hashing for operator PINs. Implemented on top of Mac because
 * SecretKeyFactory("PBKDF2WithHmacSHA256") is only available from Android API 26.
 *
 * Stored format: `pbkdf2-sha256$<iterations>$<saltHex>$<hashHex>`
 */
object PinHasher {
    private const val PREFIX = "pbkdf2-sha256"
    private const val DEFAULT_ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BYTES = 32

    private val random = SecureRandom()

    fun hash(pin: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val derived = pbkdf2(pin.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)
        return "$PREFIX\$$iterations\$${Hex.encode(salt)}\$${Hex.encode(derived)}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = Hex.decode(parts[2]) ?: return false
        val expected = Hex.decode(parts[3]) ?: return false
        val actual = pbkdf2(pin.toByteArray(Charsets.UTF_8), salt, iterations, expected.size)
        return Hmac.equalsConstantTime(expected, actual)
    }

    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC zero-pads short keys, so one zero byte is equivalent to the empty key SecretKeySpec rejects.
        mac.init(SecretKeySpec(if (password.isEmpty()) byteArrayOf(0) else password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (keyLength + hLen - 1) / hLen
        val out = ByteArray(keyLength)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val offset = (block - 1) * hLen
            System.arraycopy(t, 0, out, offset, minOf(hLen, keyLength - offset))
        }
        return out
    }

    /** Operator PINs are 4–8 digits. */
    fun isValidPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }
}

object SecureRandomBytes {
    private val random = SecureRandom()
    fun next(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}
