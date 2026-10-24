package me.longtai.smsforward.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Sends SMS through the device's default SmsManager, splitting long messages into parts. */
@Singleton
class SmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Sends [message] to [number]. Throws on failure (no permission, radio off, invalid number). */
    fun send(number: String, message: String) {
        val smsManager = smsManager()
        val parts = smsManager.divideMessage(message)
        if (parts.size <= 1) {
            smsManager.sendTextMessage(number, null, message, null, null)
        } else {
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
}
