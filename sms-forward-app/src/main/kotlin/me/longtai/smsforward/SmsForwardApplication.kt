package me.longtai.smsforward

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import me.longtai.smsforward.sms.ForwardNotifications
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class SmsForwardApplication : Application() {

    @Inject lateinit var notifications: ForwardNotifications

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Timber.plant(Timber.DebugTree())
        notifications.ensureChannels()
    }
}
