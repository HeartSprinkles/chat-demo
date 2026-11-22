package io.github.HeartSprinkles.chatdemo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutDialog(zh: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    var license by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        license = withContext(Dispatchers.IO) {
            listOf("LICENSE", "THIRD_PARTY_NOTICES.txt").joinToString("\n\n") { name ->
                runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrElse { name }
            }
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("DeepSeek Chat Demo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(label(zh, "原生 Android 聊天客户端 · 开源许可", "Native Android chat client · Open-source licenses"))
            Text(license, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(label(zh, "关闭", "Close")) } })
}
