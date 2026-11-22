package io.github.HeartSprinkles.chatdemo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun SettingsDialog(vm: ChatViewModel, zh: Boolean, dismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(preferences.language) }
    var theme by remember { mutableStateOf(preferences.theme) }
    var verifying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var about by remember { mutableStateOf(false) }
    AlertDialog(modifier = Modifier.testTag("settings-dialog"), onDismissRequest = { if (!verifying) dismiss() }, title = { Text(label(zh, "设置", "Settings")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label(zh, "语言", "Language"))
            Row { listOf("zh" to "中文", "en" to "English").forEach { (value, title) -> FilterChip(selected = language == value, onClick = { language = value }, label = { Text(title) }, modifier = Modifier.padding(end = 8.dp)) } }
            Text(label(zh, "主题", "Theme"))
            Row { listOf("system" to label(zh, "系统", "System"), "light" to label(zh, "浅色", "Light"), "dark" to label(zh, "深色", "Dark")).forEach { (value, title) -> FilterChip(selected = theme == value, onClick = { theme = value }, label = { Text(title) }, modifier = Modifier.padding(end = 8.dp)) } }
            HorizontalDivider()
            Text(label(zh, "API 密钥", "API key"))
            Text(label(zh, "密钥仅保存在本机安全存储；不会显示已保存密钥。", "Keys stay in local secure storage. Saved keys are never displayed."), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = key, onValueChange = { key = it; result = null }, label = { Text(if (state.settings.hasApiKey) label(zh, "替换密钥（留空保留）", "Replace key (blank keeps current)") else "API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !verifying)
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(label(zh, "新密钥将通过 /models 验证后保存。", "New keys are saved only after /models validation."), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { about = true }) { Text(label(zh, "关于与开源许可", "About & open-source licenses")) }
        }
    }, confirmButton = {
        TextButton(modifier = Modifier.testTag("settings-save"), enabled = !verifying, onClick = {
            if (key.isBlank()) {
                                vm.engine.updateSettings(state.settings.copy(locale = language, theme = theme))
                dismiss()
            } else {
                verifying = true
                scope.launch {
                    val validation = vm.engine.validateApiKey(key.trim())
                    verifying = false
                    validation.fold(onSuccess = {
                        vm.engine.updateSettings(state.settings.copy(locale = language, theme = theme), key.trim())
                        key = ""
                                                dismiss()
                    }, onFailure = { result = label(zh, "验证失败，请检查密钥和网络。", "Validation failed. Check your key and connection.") })
                }
            }
        }) { Text(label(zh, "保存", "Save")) }
    }, dismissButton = { TextButton(enabled = !verifying, onClick = dismiss) { Text(label(zh, "取消", "Cancel")) } })
    if (about) AboutDialog(zh) { about = false }
}
