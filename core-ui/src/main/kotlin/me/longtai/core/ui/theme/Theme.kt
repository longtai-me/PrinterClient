package me.longtai.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object StatusColors {
    val success = Color(0xFF1B873F)
    val successContainer = Color(0xFFD7F5DF)
    val error = Color(0xFFC62828)
    val errorContainer = Color(0xFFFFDAD6)
    val warning = Color(0xFFB26A00)
    val warningContainer = Color(0xFFFFE9C7)
}

/** Brand accent per app so operators can tell the two apps apart at a glance. */
enum class AppAccent(val light: Color, val dark: Color) {
    POS(Color(0xFF00658F), Color(0xFF8BCEFF)),
    TICKET(Color(0xFF6A3FB5), Color(0xFFD2BBFF)),
}

@Composable
fun PosTheme(
    accent: AppAccent = AppAccent.POS,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        darkColorScheme(primary = accent.dark, secondary = accent.dark, tertiary = StatusColors.success)
    } else {
        lightColorScheme(primary = accent.light, secondary = accent.light, tertiary = StatusColors.success)
    }
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

val MonospaceStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)
