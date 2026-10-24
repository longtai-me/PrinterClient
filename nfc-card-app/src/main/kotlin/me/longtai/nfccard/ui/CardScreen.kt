package me.longtai.nfccard.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.longtai.nfccard.data.CardKind
import me.longtai.nfccard.data.NfcCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(viewModel: CardViewModel = hiltViewModel()) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val active = cards.firstOrNull { it.id == activeId }
    val context = LocalContext.current
    var editing by remember { mutableStateOf<NfcCard?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NFC 卡模擬") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新增卡片") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ActiveCardBanner(active) }

            if (viewModel.nfcAvailability != NfcAvailability.READY) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                when (viewModel.nfcAvailability) {
                                    NfcAvailability.UNSUPPORTED -> "此手機不支援 NFC，無法模擬卡片"
                                    NfcAvailability.DISABLED -> "NFC 已關閉，請開啟後再感應"
                                    NfcAvailability.READY -> ""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (viewModel.nfcAvailability == NfcAvailability.DISABLED) {
                                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                                    Text("開啟 NFC 設定")
                                }
                            }
                        }
                    }
                }
            }

            item { InfoCard() }

            item { Text("卡片清單", style = MaterialTheme.typography.titleMedium) }

            items(cards, key = { it.id }) { card ->
                ListItem(
                    headlineContent = { Text(card.label) },
                    supportingContent = {
                        Text("${card.kind.label} · ${card.payload}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        RadioButton(selected = card.id == activeId, onClick = { viewModel.select(card.id) })
                    },
                    trailingContent = {
                        IconButton(onClick = { editing = card }) { Icon(Icons.Filled.Edit, contentDescription = "編輯") }
                    },
                    modifier = Modifier.clickable { viewModel.select(card.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (adding) {
        CardEditDialog(
            initial = null,
            onConfirm = { label, kind, payload ->
                viewModel.add(label, kind, payload)
                adding = false
            },
            onDismiss = { adding = false },
            onDelete = null,
        )
    }
    editing?.let { card ->
        CardEditDialog(
            initial = card,
            onConfirm = { label, kind, payload ->
                viewModel.update(card.copy(label = label, kind = kind, payload = payload))
                editing = null
            },
            onDismiss = { editing = null },
            onDelete = {
                viewModel.delete(card.id)
                editing = null
            },
        )
    }
}

@Composable
private fun ActiveCardBanner(active: NfcCard?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Contactless,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (active == null) {
                Text("尚未選擇卡片", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text("目前模擬", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Text(active.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${active.kind.label}：${active.payload}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "將手機背面靠近讀卡機即可送出這張卡",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                "此工具把手機模擬成一張 NDEF 標籤，內容為上方的代碼。POS／驗票 App 會以「NDEF 內容」讀取並查詢。" +
                    "注意：手機模擬時卡號 UID 每次都不同，因此「以卡號綁定」的功能無法用手機測試，請用代碼／NDEF 方式。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun CardEditDialog(
    initial: NfcCard?,
    onConfirm: (String, CardKind, String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: CardKind.MEMBER) }
    var payload by remember { mutableStateOf(initial?.payload ?: "") }
    val valid = label.isNotBlank() && payload.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增卡片" else "編輯卡片") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("名稱") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
                OutlinedTextField(
                    payload,
                    { payload = it.take(200) },
                    Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            when (kind) {
                                CardKind.MEMBER -> "會員編號（例：M0001）"
                                CardKind.TICKET -> "票券代碼（例：T-DEMO-0001）"
                                CardKind.TEXT -> "文字內容"
                            },
                        )
                    },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label, kind, payload) }, enabled = valid) { Text("儲存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("刪除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
