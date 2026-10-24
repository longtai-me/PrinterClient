package me.longtai.pos

import android.os.Bundle
import android.view.KeyEvent
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
import me.longtai.pos.ui.PosNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var hardware: Hardware

    @Inject lateinit var hardwareLifecycle: HardwareLifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hardwareLifecycle.attach(this)
        setContent {
            PosTheme(AppAccent.POS) {
                HardwareHost(hardware) {
                    AuthGate(appTitle = "收銀系統") { operator, logout ->
                        PosNavHost(operator = operator, onLogout = logout)
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        hardwareLifecycle.onKeyEvent(event) || super.dispatchKeyEvent(event)
}
