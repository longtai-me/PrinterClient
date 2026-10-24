package me.longtai.smsforward.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF00696E)
private val TealDark = Color(0xFF4DD9E0)

@Composable
fun SmsForwardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(primary = TealDark, secondary = TealDark)
    } else {
        lightColorScheme(primary = Teal, secondary = Teal)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
