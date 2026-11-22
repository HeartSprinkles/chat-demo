package io.github.HeartSprinkles.chatdemo

import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

internal data class FencedCode(val language: String, val code: String)
internal fun fencedCode(text: String): List<FencedCode> = Regex("(?m)^(`{3,}|~{3,})([^\\n]*)\\n([\\s\\S]*?)^\\1[ \\t]*$")
    .findAll(text).map { FencedCode(it.groupValues[2].trim(), it.groupValues[3].trimEnd('\n')) }.toList()

/** Conservative native token coloring; no HTML, Javascript execution, or remote grammar loading. */
internal fun highlightCode(parsed: Spanned, markdown: String): Spanned {
    val out = SpannableStringBuilder(parsed)
    val output = parsed.toString()
    var from = 0
    fencedCode(markdown).forEach { block ->
        val start = output.indexOf(block.code, from)
        if (start >= 0 && block.code.isNotEmpty()) {
            val tokens = Regex("(?m)(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|\\b(class|object|fun|val|var|if|else|for|while|return|import|package|public|private|const|let|function|def|async|await|try|catch|throw|new|true|false|null|None|True|False|SELECT|FROM|WHERE)\\b|\\b\\d+(?:\\.\\d+)?\\b")
            tokens.findAll(block.code).forEach { token ->
                val color = when { token.groups[1] != null -> 0xff718096.toInt(); token.groups[2] != null -> 0xff4b9b69.toInt(); token.groups[3] != null -> 0xffa275d8.toInt(); else -> 0xffc38443.toInt() }
                out.setSpan(ForegroundColorSpan(color), start + token.range.first, start + token.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            from = start + block.code.length
        }
    }
    return out
}

@Composable
internal fun CodeCopyButtons(text: String, zh: Boolean) {
    val clipboard = LocalClipboardManager.current
    val blocks = remember(text) { fencedCode(text) }
    blocks.forEachIndexed { index, block ->
        TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
            Text(label(zh, "复制代码", "Copy code") + " ${index + 1}" + if (block.language.isNotBlank()) " · ${block.language}" else "")
        }
    }
}
