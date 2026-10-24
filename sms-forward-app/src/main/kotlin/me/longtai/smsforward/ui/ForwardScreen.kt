package me.longtai.smsforward.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.longtai.smsforward.data.ForwardChannel
import me.longtai.smsforward.data.ForwardLogEntity
import me.longtai.smsforward.data.ForwardStatus
import me.longtai.smsforward.data.MessageTemplate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val requiredPermissions: Array<String>
    get() = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ForwardScreen(viewModel: ForwardViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val sentCount by viewModel.sentCount.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var permissionRefresh by remember { mutableIntStateOf(0) }
    val granted = remember(permissionRefresh) {
        requiredPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRefresh++
    }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("簡訊自動轉發") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            item { TransparencyCard() }

            if (!granted) {
                item { PermissionCard(onGrant = { launcher.launch(requiredPermissions) }) }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        SwitchRow(
                            title = "啟用自動轉發",
                            subtitle = if (settings.enabled) "運作中：${settings.statusSummary()}" else "總開關，目前關閉",
                            checked = settings.enabled,
                            enabled = granted || settings.enabled,
                            onCheckedChange = viewModel::setEnabled,
                            emphasize = true,
                        )
                        if (settings.enabled) {
                            Text("已成功轉發 $sentCount 則", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }

            // --- SMS channel ---
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow(
                            title = "透過簡訊轉發",
                            subtitle = "以電信簡訊轉發到指定門號（每則依資費計費）",
                            checked = settings.smsChannelEnabled,
                            onCheckedChange = viewModel::setSmsChannel,
                        )
                        OutlinedTextField(
                            value = settings.targetNumber,
                            onValueChange = viewModel::setTarget,
                            label = { Text("轉發到的門號") },
                            placeholder = { Text("例如 0912345678") },
                            singleLine = true,
                            enabled = settings.smsChannelEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // --- Telegram channel ---
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow(
                            title = "透過 Telegram Bot 轉發",
                            subtitle = "免費、不受簡訊資費限制，需自備 Bot",
                            checked = settings.telegramChannelEnabled,
                            onCheckedChange = viewModel::setTelegramChannel,
                        )
                        TokenField(
                            value = settings.telegramBotToken,
                            onValueChange = viewModel::setTelegramToken,
                            enabled = settings.telegramChannelEnabled,
                        )
                        OutlinedTextField(
                            value = settings.telegramChatId,
                            onValueChange = viewModel::setTelegramChatId,
                            label = { Text("Chat ID") },
                            supportingText = { Text("先在 Telegram 對你的 Bot 傳一則訊息，再按下方「自動偵測」") },
                            singleLine = true,
                            enabled = settings.telegramChannelEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = viewModel::detectChatId,
                            enabled = settings.telegramChannelEnabled && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("自動偵測 Chat ID") }
                        Text(
                            "取得 Bot Token：在 Telegram 搜尋 @BotFather → /newbot 建立後會給你一組 Token。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        SwitchRow(
                            title = "允許 Telegram 指令發簡訊",
                            subtitle = "在你的 Bot 聊天室輸入指令，這台手機就依指令發簡訊",
                            checked = settings.telegramCommandsEnabled,
                            onCheckedChange = viewModel::setTelegramCommands,
                            enabled = settings.telegramConfigured || settings.telegramCommandsEnabled,
                        )
                        if (settings.telegramCommandsEnabled) {
                            Text(
                                "指令：/sms 門號 內容、/reply 內容、/ping、/help。\n" +
                                    "⚠️ 只接受你設定的 Chat ID 送來的指令。持有此 Bot 者即可叫這台手機發簡訊並計費，" +
                                    "請妥善保管 Token，並使用「你與 Bot 的私人聊天室」而非群組。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // --- message template ---
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = settings.template,
                        onValueChange = viewModel::setTemplate,
                        label = { Text("轉發內容格式") },
                        supportingText = { Text("用變數自訂開頭／結尾；{body} 代表簡訊內容") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MessageTemplate.VARIABLES.forEach { v ->
                            AssistChip(onClick = { viewModel.appendTemplateVariable(v) }, label = { Text(v) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "預覽：" + MessageTemplate.render(settings.template, "0912345678", "您的驗證碼 123456", System.currentTimeMillis()).replace("\n", " ⏎ "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::resetTemplate) { Text("重設") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = settings.keywordFilter,
                    onValueChange = viewModel::setKeywordFilter,
                    label = { Text("關鍵字篩選（選填）") },
                    supportingText = { Text("以逗號分隔；留空＝全部轉發，例如：驗證碼,OTP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(onClick = viewModel::sendTest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("傳送測試訊息")
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("轉發紀錄", style = MaterialTheme.typography.titleMedium)
                    if (logs.isNotEmpty()) TextButton(onClick = viewModel::clearLogs) { Text("清除") }
                }
            }
            if (logs.isEmpty()) {
                item { Text("尚無紀錄", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(logs, key = { it.id }) { entry ->
                    LogRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    emphasize: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TokenField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Bot Token") },
        placeholder = { Text("123456:ABC-DEF...") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "隱藏" else "顯示",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TransparencyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.padding(start = 12.dp)) {
                Text("關於此功能", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "轉發開啟時，狀態列會持續顯示通知，讓使用這台裝置的人都知道簡訊正在被轉發。" +
                        "請僅在這台裝置與目標門號／Telegram 都屬於你本人時使用；轉發他人不知情的簡訊可能違法。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text("需要簡訊與通知權限", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "需要「接收簡訊」權限才能讀取收到的簡訊；使用簡訊通道還需「傳送簡訊」權限，並建議開啟通知以顯示運作狀態。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Button(onClick = onGrant) { Text("授予權限") }
        }
    }
}

private val logTimeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN)

@Composable
private fun LogRow(entry: ForwardLogEntity) {
    val status = runCatching { ForwardStatus.valueOf(entry.status) }.getOrNull()
    val channel = runCatching { ForwardChannel.valueOf(entry.channel) }.getOrNull() ?: ForwardChannel.GENERAL
    val color = when (status) {
        ForwardStatus.SENT -> MaterialTheme.colorScheme.primary
        ForwardStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${channel.label} · ${entry.fromNumber} → ${entry.destination}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status == ForwardStatus.SENT) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.padding(end = 4.dp))
                }
                Text(status?.label ?: entry.status, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        Text(
            logTimeFormat.format(Date(entry.receivedAt)) + " · " + entry.preview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entry.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
