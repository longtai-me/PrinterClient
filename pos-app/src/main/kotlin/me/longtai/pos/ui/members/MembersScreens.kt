package me.longtai.pos.ui.members

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.longtai.core.hardware.feedback.Feedback
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.ui.components.AppTopBar
import me.longtai.core.ui.components.EmptyState
import me.longtai.core.ui.components.SwitchRow
import me.longtai.core.ui.files.CsvFiles
import me.longtai.core.ui.hardware.NfcEffect
import me.longtai.pos.data.MemberRepository
import me.longtai.pos.data.ValidationException
import me.longtai.pos.domain.model.Member
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MembersViewModel @Inject constructor(
    private val repository: MemberRepository,
    private val files: CsvFiles,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val members: StateFlow<List<Member>> = _query.flatMapLatest { repository.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    /** Tapping a card on the list screen opens the matching member. */
    suspend fun memberIdForCard(tag: NfcTag): Long? = repository.findByCard(tag.uid)?.id

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching { files.write(uri, repository.exportCsv()) }
            .onSuccess { _messages.emit("已匯出會員資料") }
            .onFailure { _messages.emit("匯出失敗：${it.message}") }
    }
}

@Composable
fun MembersScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: MembersViewModel = hiltViewModel()) {
    val members by viewModel.members.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    NfcEffect { tag ->
        scope.launch {
            val id = viewModel.memberIdForCard(tag)
            if (id != null) onOpen(id) else snackbar.showSnackbar("此卡未綁定會員（${tag.uid}）")
        }
    }

    Scaffold(
        topBar = {
            AppTopBar("會員管理", onBack = onBack) {
                IconButton(onClick = { exportLauncher.launch("members-${LocalDate.now()}.csv") }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "匯出")
                }
            }
        },
        floatingActionButton = { FloatingActionButton(onClick = { onOpen(0) }) { Icon(Icons.Filled.Add, contentDescription = "新增會員") } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("搜尋姓名 / 會員編號 / 手機，或感應會員卡") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (members.isEmpty()) {
                EmptyState(Icons.Filled.People, "沒有會員資料")
            } else {
                LazyColumn {
                    items(members, key = { it.id }) { m ->
                        ListItem(
                            headlineContent = { Text(m.name + if (!m.active) "（停用）" else "") },
                            supportingContent = {
                                Text(listOfNotNull(m.memberNo, m.phone, if (m.cardUid != null) "已綁卡" else null).joinToString(" · "))
                            },
                            trailingContent = { Text("${m.points} 點") },
                            modifier = Modifier.clickable { onOpen(m.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

data class MemberForm(
    val id: Long = 0,
    val memberNo: String = "",
    val name: String = "",
    val phone: String = "",
    val cardUid: String = "",
    val discountPercent: String = "",
    val points: Long = 0,
    val active: Boolean = true,
)

@HiltViewModel
class MemberEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: MemberRepository,
    private val feedback: Feedback,
) : ViewModel() {
    private val memberId: Long = savedState.get<Long>("id") ?: 0L
    val isNew: Boolean get() = memberId == 0L

    private val _form = MutableStateFlow(MemberForm(cardUid = savedState.get<String>("uid").orEmpty()))
    val form: StateFlow<MemberForm> = _form.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    init {
        if (memberId != 0L) {
            viewModelScope.launch {
                repository.get(memberId)?.let { m ->
                    _form.value = MemberForm(
                        m.id, m.memberNo, m.name, m.phone.orEmpty(), m.cardUid.orEmpty(),
                        if (m.discountBp == 0) "" else (m.discountBp / 100.0).toString().removeSuffix(".0"), m.points, m.active,
                    )
                }
            }
        }
    }

    fun update(transform: (MemberForm) -> MemberForm) = _form.update(transform)

    fun onNfc(tag: NfcTag) {
        feedback.success()
        _form.update { it.copy(cardUid = tag.uid) }
        _messages.tryEmit("已讀取卡號 ${tag.uid}")
    }

    fun save() {
        val f = _form.value
        val percent = f.discountPercent.trim().ifEmpty { "0" }.toDoubleOrNull()
        if (percent == null || percent < 0 || percent > 100) {
            _messages.tryEmit("折扣需介於 0–100%")
            return
        }
        viewModelScope.launch {
            try {
                repository.save(
                    Member(
                        id = f.id, memberNo = f.memberNo, name = f.name, phone = f.phone, cardUid = f.cardUid.ifBlank { null },
                        discountBp = Math.round(percent * 100).toInt(), points = f.points, active = f.active,
                    ),
                )
                _messages.emit("已儲存")
                _saved.emit(Unit)
            } catch (e: ValidationException) {
                _messages.emit(e.message ?: "儲存失敗")
            }
        }
    }
}

@Composable
fun MemberEditScreen(onBack: () -> Unit, viewModel: MemberEditViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.saved.collect { onBack() } }
    NfcEffect(viewModel::onNfc)

    Scaffold(
        topBar = { AppTopBar(if (viewModel.isNew) "新增會員" else "會員資料", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(form.memberNo, { v -> viewModel.update { it.copy(memberNo = v.take(20)) } }, Modifier.fillMaxWidth(), label = { Text("會員編號 *") }, singleLine = true)
            OutlinedTextField(form.name, { v -> viewModel.update { it.copy(name = v.take(30)) } }, Modifier.fillMaxWidth(), label = { Text("姓名 *") }, singleLine = true)
            OutlinedTextField(
                form.phone, { v -> viewModel.update { it.copy(phone = v.filter { c -> c.isDigit() || c == '-' || c == '+' }.take(20)) } },
                Modifier.fillMaxWidth(), label = { Text("手機") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            OutlinedTextField(
                form.discountPercent, { v -> viewModel.update { it.copy(discountPercent = v.filter { c -> c.isDigit() || c == '.' }.take(5)) } },
                Modifier.fillMaxWidth(), label = { Text("會員折扣 %（例：10 = 9 折）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(if (form.cardUid.isBlank()) "尚未綁定會員卡" else "卡號 ${form.cardUid}") },
                    supportingContent = { Text("將會員卡靠近裝置背面 NFC 感應區即可綁定") },
                    leadingContent = { Icon(Icons.Filled.Nfc, contentDescription = null) },
                    trailingContent = {
                        if (form.cardUid.isNotBlank()) TextButton(onClick = { viewModel.update { it.copy(cardUid = "") } }) { Text("解除") }
                    },
                )
            }
            if (!viewModel.isNew) Text("目前點數：${form.points}", style = MaterialTheme.typography.titleMedium)
            SwitchRow("啟用", form.active, { v -> viewModel.update { it.copy(active = v) } })
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("儲存") }
        }
    }
}
