package io.github.HeartSprinkles.chatdemo

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.util.LruCache
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.delay

private class MarkdownRenderer(context: android.content.Context) {
    val markwon = Markwon.builder(context).usePlugin(TablePlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver(object : LinkResolver {
                    override fun resolve(view: android.view.View, link: String) { openSafe(view.context, link) }
                })
            }
        }).build()
    val cache = LruCache<String, android.text.Spanned>(32)
    fun render(text: String): android.text.Spanned = cache.get(text) ?: highlightCode(markwon.toMarkdown(text), text).also { cache.put(text, it) }
}

internal fun openSafe(context: android.content.Context, url: String) {
    val uri = Uri.parse(url)
    if ((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
internal fun MarkdownText(text: String, streaming: Boolean) {
    val context = LocalContext.current
    val renderer = remember(context) { MarkdownRenderer(context) }
    var renderedText by remember { mutableStateOf(text) }
    // Sampling, not debounce: continuous streams still display new text every 120 ms.
    val currentText by rememberUpdatedState(text)
    LaunchedEffect(streaming) {
        if (streaming) while (true) { renderedText = currentText; delay(120) }
    }
    LaunchedEffect(text, streaming) { if (!streaming) renderedText = text }
    var parsed by remember(renderer, renderedText) { mutableStateOf<android.text.Spanned?>(null) }
    LaunchedEffect(renderedText, renderer) {
        parsed = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { renderer.render(renderedText) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            android.text.SpannableStringBuilder(renderedText)
        }
    }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(modifier = Modifier.testTag(if (parsed == null) "markdown-loading" else "markdown-ready"), factory = { TextView(it).apply { textSize = 16f; setTextIsSelectable(true); setPadding(0, 8, 0, 8) } }, update = { view ->
        view.setTextColor(color)
        parsed?.let { content -> if (view.tag !== content) {
            try { renderer.markwon.setParsedMarkdown(view, content) }
            catch (_: Exception) { view.text = content.toString() }
            view.tag = content
        } }
    })
}

@Composable
internal fun CitationLinks(text: String, zh: Boolean) {
    val context = LocalContext.current
    val urls = remember(text) { Regex("https?://[^\\s\\\"<>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '}') }.distinct().take(20).toList() }
    urls.forEachIndexed { index, url -> TextButton(onClick = { openSafe(context, url) }) { Text("${index + 1}. ${Uri.parse(url).host ?: label(zh, "来源", "Source")}") } }
}
