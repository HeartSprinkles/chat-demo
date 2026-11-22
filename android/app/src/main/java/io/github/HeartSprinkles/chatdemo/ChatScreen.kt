package io.github.HeartSprinkles.chatdemo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HeartSprinkles.chatdemo.core.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, zh: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engine = vm.engine
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var settings by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Conversation?>(null) }
    var delete by remember { mutableStateOf<Conversation?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<ChatMessage?>(null) }
    val list = rememberLazyListState()
    // Only follow the tail if already at the tail. Never move a reader to new tokens.
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(state.selectedId) { if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex) }
    LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.size) {
        if (atBottom && state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text(label(zh, "对话", "Conversations"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
            Button(onClick = { engine.create(); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(label(zh, "新建对话", "New conversation")) }
            LazyColumn(Modifier.weight(1f)) {
                items(state.conversations, key = { it.id }) { conversation ->
                    NavigationDrawerItem(label = { Text(conversation.title, maxLines = 2) }, selected = conversation.id == state.selectedId,
                        onClick = { engine.select(conversation.id); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 8.dp), badge = {
                            Row { TextButton(onClick = { rename = conversation }) { Text(label(zh, "改名", "Rename")) }; TextButton(onClick = { delete = conversation }) { Text(label(zh, "删除", "Delete")) } }
                        })
                }
            }
            TextButton(onClick = { clearAll = true }, modifier = Modifier.padding(horizontal = 16.dp), enabled = state.conversations.isNotEmpty()) { Text(label(zh, "清空全部对话", "Clear all conversations")) }
            TextButton(onClick = { settings = true; scope.launch { drawer.close() } }, modifier = Modifier.padding(16.dp)) { Text(label(zh, "设置", "Settings")) }
        }
    }) {
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
            TopAppBar(title = { Text(state.conversation?.title ?: "DeepSeek Chat Demo", maxLines = 1) }, navigationIcon = {
                TextButton(onClick = { scope.launch { drawer.open() } }) { Text("☰") }
            }, actions = { TextButton(modifier = Modifier.testTag("toolbar-settings"), onClick = { settings = true }) { Text(label(zh, "设置", "Settings")) } })
        }, bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var models by remember { mutableStateOf(false) }
                        Box { TextButton(onClick = { models = true }) { Text(state.settings.model) }
                            DropdownMenu(expanded = models, onDismissRequest = { models = false }) {
                                listOf("deepseek-flash", "deepseek-v4-pro").forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { engine.updateSettings(state.settings.copy(model = name)); models = false }) }
                            }
                        }
                        FilterChip(selected = state.settings.thinking, onClick = { engine.updateSettings(state.settings.copy(thinking = !state.settings.thinking)) }, label = { Text(label(zh, "思考", "Thinking")) })
                        FilterChip(selected = state.settings.search, onClick = { engine.updateSettings(state.settings.copy(search = !state.settings.search)) }, label = { Text(label(zh, "搜索", "Search")) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = state.draft, onValueChange = engine::updateDraft, modifier = Modifier.weight(1f), maxLines = 5,
                            placeholder = { Text(label(zh, "输入消息…", "Message…")) })
                        if (state.isStreaming) Button(onClick = engine::stop) { Text(label(zh, "停止", "Stop")) }
                        else Button(onClick = { engine.send() }, enabled = state.draft.isNotBlank() && !state.isLoading && state.activeConversationId == null && state.settings.hasApiKey) { Text(label(zh, "发送", "Send")) }
                    }
                    if (!state.settings.hasApiKey) TextButton(onClick = { settings = true }) { Text(label(zh, "先在设置中添加 API 密钥", "Add an API key in Settings first")) }
                    if (state.activeConversationId != null && !state.isStreaming) Text(label(zh, "另一对话正在生成", "Another conversation is generating"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer) { Row(Modifier.padding(8.dp)) { Text(error, modifier = Modifier.weight(1f)); TextButton(onClick = engine::clearError) { Text(label(zh, "关闭", "Dismiss")) } } }
                }
                if (state.messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(label(zh, "开始新的对话", "Start a conversation")) }
                else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageCard(message, zh, state.activeConversationId == null, onEdit = { edit = message }, onRegenerate = { engine.regenerate(message.id) }, onVersion = { engine.selectVersion(message.id, it) })
                    }
                    if (state.canContinue && state.activeConversationId == null) item(key = "continue") { TextButton(onClick = engine::continueReply) { Text(label(zh, "继续回复", "Continue reply")) } }
                }
                if (!atBottom && state.messages.isNotEmpty()) TextButton(onClick = { scope.launch { list.animateScrollToItem(state.messages.lastIndex) } }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text(label(zh, "↓ 最新消息", "↓ Latest")) }
            }
        }
    }
    if (settings) SettingsDialog(vm, zh) { settings = false }
    if (clearAll) AlertDialog(onDismissRequest = { clearAll = false }, title = { Text(label(zh, "清空全部对话？", "Clear all conversations?")) }, text = { Text(label(zh, "全部对话和草稿将被永久删除；当前生成会停止。API 密钥和设置将保留。", "All conversations and drafts will be permanently deleted and generation stopped. Your API key and settings will remain.")) }, confirmButton = { TextButton(onClick = { engine.clearAll(); clearAll = false; scope.launch { drawer.close() } }) { Text(label(zh, "确认清空", "Confirm clear all")) } }, dismissButton = { TextButton(onClick = { clearAll = false }) { Text(label(zh, "取消", "Cancel")) } })
    rename?.let { conversation -> TextEntryDialog(label(zh, "重命名对话", "Rename conversation"), conversation.title, zh, onDismiss = { rename = null }) { engine.rename(conversation.id, it); rename = null } }
    edit?.let { message -> TextEntryDialog(label(zh, "编辑并生成分支", "Edit and branch"), message.text, zh, onDismiss = { edit = null }) { engine.edit(message.id, it); edit = null } }
    delete?.let { conversation -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(label(zh, "删除对话？", "Delete conversation?")) }, text = { Text(label(zh, "此操作会永久删除“${conversation.title}”。", "Permanently delete “${conversation.title}”?")) }, confirmButton = { TextButton(onClick = { engine.delete(conversation.id); delete = null }) { Text(label(zh, "删除", "Delete")) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text(label(zh, "取消", "Cancel")) } }) }
}

@Composable
internal fun TextEntryDialog(title: String, initial: String, zh: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 10) }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text(label(zh, "保存", "Save")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(label(zh, "取消", "Cancel")) } })
}

@Composable
internal fun MessageCard(message: ChatMessage, zh: Boolean, idle: Boolean, onEdit: () -> Unit, onRegenerate: () -> Unit, onVersion: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(message.id) { mutableStateOf(false) }
    var thinking by remember(message.id) { mutableStateOf(false) }
    val user = message.role == "user"
    Surface(shape = MaterialTheme.shapes.medium, color = if (user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(if (user) label(zh, "你", "You") else label(zh, "助手", "Assistant"), style = MaterialTheme.typography.labelLarge)
            if (message.thinking.isNotBlank()) {
                TextButton(onClick = { thinking = !thinking }) { Text(label(zh, if (thinking) "收起思考" else "展开思考", if (thinking) "Hide thinking" else "Show thinking")) }
                if (thinking) MarkdownText(message.thinking, message.status == "streaming")
            }
            val long = message.text.length > 4000
            MarkdownText(if (long && !expanded) message.text.take(3500) + "\n\n…" else message.text, message.status == "streaming")
            CodeCopyButtons(message.text, zh)
            if (message.citations.isNotEmpty()) CitationLinks(message.citations.joinToString("\n"), zh)
            if (long) TextButton(onClick = { expanded = !expanded }) { Text(label(zh, if (expanded) "收起" else "展开完整消息", if (expanded) "Collapse" else "Show full message")) }
            message.tools.forEach { tool ->
                var open by remember(tool.id) { mutableStateOf(false) }
                TextButton(onClick = { open = !open }) { Text(tool.title.ifBlank { tool.name }) }
                if (open) { Text(tool.result.take(4000)); CitationLinks(tool.result + "\n" + tool.urls.joinToString("\n"), zh) }
            }
            if (message.status != "complete") Text(message.status, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Text(label(zh, "复制", "Copy")) }
                TextButton(enabled = idle, onClick = if (user) onEdit else onRegenerate) { Text(if (user) label(zh, "编辑", "Edit") else label(zh, "重新生成", "Regenerate")) }
            }
            if (message.versions.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                message.versions.forEachIndexed { index, version ->
                    FilterChip(selected = message.selectedVersionId == version.id, onClick = { onVersion(version.id) }, enabled = idle, label = { Text("${index + 1}") }, modifier = Modifier.padding(end = 4.dp))
                }
            }
        }
    }
}
