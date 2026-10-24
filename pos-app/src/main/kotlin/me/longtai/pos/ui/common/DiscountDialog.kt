package me.longtai.pos.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.longtai.core.common.money.MoneyFormat
import me.longtai.pos.domain.model.Discount

/** Percentage-off presets expressed the way Taiwanese cashiers say them (9折 = 10% off). */
private val PRESETS = listOf(500, 1000, 1500, 2000, 3000, 5000)

/** "9折", "85折", "折 $20" */
fun describe(discount: Discount, money: MoneyFormat): String = when (discount) {
    is Discount.Percent -> {
        val remaining = 10_000 - discount.basisPoints
        when {
            discount.basisPoints == 0 -> "無折扣"
            remaining == 0 -> "免費"
            remaining % 1000 == 0 -> "${remaining / 1000}折"
            remaining % 100 == 0 -> "${remaining / 100}折"
            else -> "${remaining / 100.0}折"
        }
    }
    is Discount.Amount -> "折 ${money.format(discount.amount)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscountDialog(
    title: String,
    current: Discount?,
    money: MoneyFormat,
    onApply: (Discount?) -> Unit,
    onDismiss: () -> Unit,
) {
    var percent by remember { mutableStateOf((current as? Discount.Percent)?.basisPoints) }
    var amountText by remember { mutableStateOf((current as? Discount.Amount)?.let { money.formatPlain(it.amount) } ?: "") }
    var customPercent by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("百分比折扣", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { bp ->
                        FilterChip(
                            selected = percent == bp,
                            onClick = {
                                percent = bp
                                amountText = ""
                                customPercent = ""
                            },
                            label = { Text(describe(Discount.Percent(bp), money)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = customPercent,
                    onValueChange = { v ->
                        customPercent = v.filter { it.isDigit() }.take(2)
                        percent = customPercent.toIntOrNull()?.let { it * 100 }
                        amountText = ""
                        error = null
                    },
                    label = { Text("自訂折扣 %（例：12 表示打 88 折）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("或折讓固定金額", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { v ->
                        amountText = v.filter { it.isDigit() || it == '.' }.take(10)
                        percent = null
                        customPercent = ""
                        error = null
                    },
                    label = { Text("折讓金額") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = percent
                when {
                    p != null && p in 1..10_000 -> onApply(Discount.Percent(p))
                    amountText.isNotBlank() -> {
                        val amount = money.parse(amountText)
                        if (amount == null || !amount.isPositive) error = "金額格式錯誤" else onApply(Discount.Amount(amount))
                    }
                    else -> error = "請選擇折扣"
                }
            }) { Text("套用") }
        },
        dismissButton = {
            Column {
                if (current != null) TextButton(onClick = { onApply(null) }) { Text("取消折扣", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("關閉") }
            }
        },
    )
}
