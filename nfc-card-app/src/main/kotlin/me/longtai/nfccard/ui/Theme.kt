package me.longtai.nfccard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFF8A5000)
private val AmberDark = Color(0xFFFFB868)

@Composable
fun NfcCardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(primary = AmberDark, secondary = AmberDark)
    } else {
        lightColorScheme(primary = Amber, secondary = Amber)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
