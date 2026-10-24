package me.longtai.ticket

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import me.longtai.core.auth.ui.AuthGate
import me.longtai.core.hardware.Hardware
import me.longtai.core.hardware.HardwareLifecycle
import me.longtai.core.ui.hardware.HardwareHost
import me.longtai.core.ui.theme.AppAccent
import me.longtai.core.ui.theme.PosTheme
import me.longtai.ticket.ui.TicketNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var hardware: Hardware

    @Inject lateinit var hardwareLifecycle: HardwareLifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Gate staff scan continuously; the screen must not sleep between guests.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hardwareLifecycle.attach(this)
        setContent {
            PosTheme(AppAccent.TICKET) {
                HardwareHost(hardware) {
                    AuthGate(appTitle = "票券驗票系統") { operator, logout ->
                        TicketNavHost(operator = operator, onLogout = logout)
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        hardwareLifecycle.onKeyEvent(event) || super.dispatchKeyEvent(event)
}
