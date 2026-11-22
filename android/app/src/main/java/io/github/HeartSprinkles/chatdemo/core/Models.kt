package io.github.HeartSprinkles.chatdemo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

internal fun newId(): String = UUID.randomUUID().toString()
internal val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

@Serializable data class Settings(val model: String = "deepseek-flash", val thinking: Boolean = false, val search: Boolean = false, val hasApiKey: Boolean = false, val locale: String = "system", val theme: String = "system")
@Serializable data class ToolActivity(val id: String, val name: String, val input: String = "", val result: String = "", val title: String = "", val urls: List<String> = emptyList())
data class MessageVersion(val id: String, val text: String, val thinking: String = "", val status: String = "complete")
@Serializable data class ChatMessage(val id: String = newId(), val role: String, val text: String = "", val thinking: String = "", val tools: List<ToolActivity> = emptyList(), val status: String = "complete", val stopReason: String? = null, val parentId: String? = null, val selectedChildId: String? = null, val blocks: List<JsonObject> = emptyList(), val prefix: Boolean = true, val citations: List<String> = emptyList(), val usage: JsonObject? = null, val stopDetails: JsonObject? = null, val model: String = "deepseek-flash", val thinkingEnabled: Boolean = false, val latestReminder: String? = null, @kotlinx.serialization.Transient val versions: List<MessageVersion> = emptyList(), @kotlinx.serialization.Transient val selectedVersionId: String? = null)
@Serializable data class Conversation(val id: String = newId(), val title: String = "New chat", val draft: String = "", val messages: List<ChatMessage> = emptyList(), val updatedAt: Long = System.currentTimeMillis(), val rootId: String? = null, val manualTitle: Boolean = false)
data class AppState(val settings: Settings = Settings(), val conversations: List<Conversation> = emptyList(), val selectedId: String? = null, val isLoading: Boolean = true, val error: String? = null, val activeConversationId: String? = null, val conversation: Conversation? = null, val newDraft: String = "", val messageVersions: Map<String,List<MessageVersion>> = emptyMap()) {
    val messages: List<ChatMessage> get() = conversation?.messages.orEmpty()
    val draft: String get() = conversation?.draft ?: newDraft
    val isStreaming: Boolean get() = activeConversationId != null
    val canContinue: Boolean get() = !isStreaming && messages.lastOrNull()?.let(::canContinueMessage) == true
}

fun visibleMessages(c: Conversation): List<ChatMessage> {
    val remaining = c.messages.associateBy { it.id }.toMutableMap()
    val path = mutableListOf<ChatMessage>()
    var id = c.rootId
    while (id != null) { val m = remaining.remove(id) ?: break; path += m; id = m.selectedChildId }
    return path
}
fun appendBranch(c: Conversation, parent: String?, nodes: List<ChatMessage>): Conversation {
    if (nodes.isEmpty()) return c
    return c.copy(rootId = if (parent == null) nodes.first().id else c.rootId, updatedAt = System.currentTimeMillis(), messages = c.messages.map { if (it.id == parent) it.copy(selectedChildId = nodes.first().id) else it } + nodes.mapIndexed { i,m -> m.copy(parentId = if (i == 0) parent else nodes[i-1].id, selectedChildId = nodes.getOrNull(i+1)?.id) })
}
fun selectBranch(c: Conversation, target: String): Conversation {
    val node = c.messages.find { it.id == target } ?: return c
    if (visibleMessages(c).none { it.parentId == node.parentId }) return c
    return c.copy(rootId = if (node.parentId == null) node.id else c.rootId, messages = c.messages.map { if (it.id == node.parentId) it.copy(selectedChildId = node.id) else it })
}
fun canContinueMessage(m: ChatMessage): Boolean = m.role == "assistant" && m.prefix && m.stopReason !in listOf("refusal", "tool_use") && m.blocks.none { it.str("type") in listOf("server_tool_use", "web_search_tool_result", "web_fetch_tool_result") || (it["isTemplate"] as? JsonPrimitive)?.booleanOrNull == true } && (m.status == "cancelled" || (m.status == "complete" && m.stopReason == "max_tokens"))

internal fun projected(m: ChatMessage): ChatMessage {
    val tools = m.blocks.filter { it.str("type").contains("tool") }.map { b ->
        val content = b["content"]
        val urls = Regex("https?://[^\\s\\\"<>]+").findAll(b.toString()).map { it.value.replace("\\/", "/") }.distinct().toList()
        val readable=when(content) {
            is JsonArray -> content.mapNotNull { it as? JsonObject }.joinToString("\n\n") { listOf(it.str("title"),it.str("url"),it.str("snippet"),it.str("error_code")).filter(String::isNotBlank).joinToString("\n") }
            is JsonObject -> listOf((content["content"] as? JsonObject)?.str("title").orEmpty(),content.str("url"),content.str("error_code")).filter(String::isNotBlank).joinToString("\n")
            else -> ""
        }
        ToolActivity(b.str("id").ifEmpty { b.str("tool_use_id") }, b.str("name").ifEmpty { b.str("kind") }, b["input"]?.toString().orEmpty(), readable, ((content as? JsonObject)?.get("content") as? JsonObject)?.str("title").orEmpty(), urls)
    }
    return m.copy(text = m.blocks.filter { it.str("type") == "text" }.joinToString("") { it.str("text") }, thinking = m.blocks.filter { it.str("type") == "thinking" }.joinToString("") { it.str("thinking") }, tools = tools, citations = m.blocks.flatMap { (it["citations"] as? JsonArray).orEmpty().mapNotNull { c -> (c as? JsonObject)?.str("url")?.takeIf { url -> url.startsWith("https://") || url.startsWith("http://") } } }.distinct())
}

fun assistantContext(m: ChatMessage, prefix: Boolean = false, thinking: Boolean = false): JsonArray {
    val unsigned = m.blocks.filter { it.str("type") == "web_fetch_tool_result" && it.str("kind") == "read_link" && (it["content"] as? JsonObject)?.str("type") == "web_fetch_result" && it.str("signature").isBlank() }.map { it.str("tool_use_id") }.toSet()
    val out = mutableListOf<JsonObject>(); var adjacent = false
    for (b in m.blocks) {
        when (b.str("type")) {
            "text" -> { val text = b.str("text"); if (adjacent && out.isNotEmpty()) { val old = out.removeAt(out.lastIndex); out += buildJsonObject { put("type","text"); put("text",old.str("text")+text) } } else if (text.isNotEmpty()) out += buildJsonObject { put("type","text"); put("text", (if (out.lastOrNull()?.str("type") == "text") "\n\n" else "")+text) }; adjacent = true }
            "thinking" -> { if (prefix && b.str("thinking").isBlank()) continue; adjacent = false; if (prefix && thinking) out += buildJsonObject { put("type","thinking"); put("thinking",b.str("thinking")); put("signature",b.str("signature")) } }
            else -> { adjacent = false; if (b.str("kind") == "read_link" && b.str("id").ifEmpty { b.str("tool_use_id") } !in unsigned) { if (b.str("type") == "server_tool_use") out += JsonObject(b.filterKeys { it != "caller" }) else if (b.str("type") == "web_fetch_tool_result") out += b } }
        }
    }
    return JsonArray(out)
}
fun requestMessages(history: List<ChatMessage>, question: ChatMessage, prefix: ChatMessage? = null, thinking: Boolean = false): JsonArray {
    val out = mutableListOf<JsonObject>()
    fun user(m:ChatMessage) = buildJsonObject { put("role","user"); put("content",m.text) }
    fun assistant(m:ChatMessage,p:Boolean=false) = buildJsonObject { put("role","assistant"); put("content",assistantContext(m,p,thinking)) }
    var i = 0
    while (i+1 < history.size) { val u=history[i]; val a=history[i+1]; if (u.role=="user" && u.text.isNotBlank() && a.role=="assistant" && a.status in listOf("complete","cancelled") && a.stopReason != "refusal" && a.text.isNotEmpty()) { out += user(u); out += assistant(a); i+=2 } else i++ }
    out += user(question)
    if (prefix != null && prefix.prefix && assistantContext(prefix,true,thinking).any { (it as JsonObject).str("text").isNotBlank() || it.str("thinking").isNotBlank() }) out += assistant(prefix,true)
    return JsonArray(out)
}
