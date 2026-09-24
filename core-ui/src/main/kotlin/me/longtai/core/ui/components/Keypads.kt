package me.longtai.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val BACKSPACE = "⌫"

/** 3×4 numeric keypad used for PINs, quantities and cash amounts. */
@Composable
fun NumericKeypad(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    extraKey: String = "",
    onExtraKey: () -> Unit = {},
    keyHeight: Int = 56,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(extraKey, "0", BACKSPACE),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Box(Modifier.weight(1f)) {
                        if (key.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = {
                                    when (key) {
                                        BACKSPACE -> onBackspace()
                                        extraKey -> onExtraKey()
                                        else -> onKey(key[0])
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(keyHeight.dp),
                            ) {
                                if (key == BACKSPACE) {
                                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "刪除")
                                } else {
                                    Text(key, style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Filled/empty dots indicating how many PIN digits were entered. */
@Composable
fun PinDots(length: Int, max: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(max) { index ->
            Surface(
                modifier = Modifier.size(16.dp),
                shape = CircleShape,
                color = if (index < length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}

/** Applies a keypad key to an amount string, allowing at most [decimals] fraction digits. */
fun appendAmountKey(current: String, key: Char, decimals: Int, maxLength: Int = 10): String {
    if (current.length >= maxLength) return current
    if (key == '.') {
        if (decimals == 0 || current.contains('.')) return current
        return if (current.isEmpty()) "0." else "$current."
    }
    if (!key.isDigit()) return current
    val dot = current.indexOf('.')
    if (dot >= 0 && current.length - dot - 1 >= decimals) return current
    if (current == "0") return key.toString()
    return current + key
}

@Composable
fun KeypadDisplay(text: String, modifier: Modifier = Modifier, placeholder: String = "0") {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text.ifEmpty { placeholder },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.headlineMedium,
            color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
