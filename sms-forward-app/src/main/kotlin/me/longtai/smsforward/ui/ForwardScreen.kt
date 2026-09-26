package me.longtai.smsforward.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.longtai.smsforward.data.ForwardLogEntity
import me.longtai.smsforward.data.ForwardStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val requiredPermissions: Array<String>
    get() = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(viewModel: ForwardViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val sentCount by viewModel.sentCount.collectAsStateWithLifecycle()
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TransparencyCard() }

            if (!granted) {
                item {
                    PermissionCard(onGrant = { launcher.launch(requiredPermissions) })
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("啟用簡訊自動轉發", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (settings.enabled) "運作中，收到的簡訊會轉發" else "目前關閉",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.enabled,
                                enabled = granted || settings.enabled,
                                onCheckedChange = { viewModel.setEnabled(it) },
                            )
                        }
                        if (settings.enabled) {
                            Text(
                                "已成功轉發 $sentCount 則",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = settings.targetNumber,
                    onValueChange = viewModel::setTarget,
                    label = { Text("轉發到的門號") },
                    placeholder = { Text("例如 0912345678") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("附上來源與時間", style = MaterialTheme.typography.bodyLarge)
                        Text("轉發內容開頭加上原寄件人與收到時間", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.includeSenderInfo, onCheckedChange = viewModel::setIncludeSenderInfo)
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
                OutlinedButton(onClick = viewModel::sendTest, modifier = Modifier.fillMaxWidth()) {
                    Text("傳送測試訊息到此門號")
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
private fun TransparencyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.padding(start = 12.dp)) {
                Text("關於此功能", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "轉發開啟時，狀態列會持續顯示通知，讓使用這台裝置的人都知道簡訊正在被轉發。" +
                        "請僅在這台裝置與目標門號都屬於你本人時使用；轉發他人不知情的簡訊可能違法。",
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
                "需要「接收簡訊」「傳送簡訊」權限才能轉發，並建議開啟通知以顯示運作狀態。",
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
    val color = when (status) {
        ForwardStatus.SENT -> MaterialTheme.colorScheme.primary
        ForwardStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${entry.fromNumber} → ${entry.targetNumber}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status == ForwardStatus.SENT) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.padding(end = 4.dp))
                Text(status?.label ?: entry.status, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        Text(logTimeFormat.format(Date(entry.receivedAt)) + " · " + entry.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        entry.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
