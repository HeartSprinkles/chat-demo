package io.github.HeartSprinkles.chatdemo.core

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStreamReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Incremental WHATWG framing: CR, LF, CRLF, comments, persistent IDs and multiline data. */
data class SseFrame(val event: String, val data: String, val lastEventId: String)
class SseDecoder {
    private var buffer = ""
    private var data = mutableListOf<String>()
    private var event = ""
    private var id = ""
    private var first = true
    fun push(chunk: String): List<SseFrame> {
        var text = chunk
        if (first && text.isNotEmpty()) { first=false; text=text.removePrefix("\uFEFF") }
        buffer += text
        val out = mutableListOf<SseFrame>(); var start=0; var i=0
        while (i < buffer.length) {
            val ch=buffer[i]
            if (ch != '\r' && ch != '\n') { i++; continue }
            if (ch=='\r' && i==buffer.lastIndex) break
            line(buffer.substring(start,i))?.let(out::add)
            i += if (ch=='\r' && buffer.getOrNull(i+1)=='\n') 2 else 1
            start=i
        }
        buffer=buffer.substring(start)
        return out
    }
    fun end(): List<SseFrame> { val frame=if(buffer.endsWith('\r')) line(buffer.dropLast(1)) else null; buffer=""; data.clear(); event=""; return listOfNotNull(frame) }
    private fun line(line: String): SseFrame? {
        if (line.isEmpty()) { val out=if(data.isNotEmpty()) SseFrame(event.ifEmpty{"message"},data.joinToString("\n"),id) else null; data.clear();event="";return out }
        if (line.startsWith(':')) return null
        val field=line.substringBefore(':'); val value=if(':' in line) line.substringAfter(':').removePrefix(" ") else ""
        when(field) { "data" -> data.add(value); "event" -> event=value; "id" -> if('\u0000' !in value) id=value }
        return null
    }
}
class ApiException(val kind: String, message: String, val status: Int? = null): IOException(message)
class ReplyAccumulator(initial: ChatMessage) {
    var message = initial.copy(status="streaming"); private set
    var title: String? = null; private set
    private val indexes=mutableMapOf<Int,Int>()
    fun apply(event: JsonObject) {
        when(event.str("type")) {
            "message_start" -> { val start=event["message"] as? JsonObject;message=message.copy(usage=start?.get("usage") as? JsonObject ?: message.usage) }
            "content_block_start" -> {
                val index=(event["index"] as? JsonPrimitive)?.intOrNull ?: return
                if(index<0) return
                indexes.remove(index)
                val block=event["content_block"] as? JsonObject ?: return
                val type=block.str("type")
                if(type=="tool_use") { message=message.copy(prefix=false);return }
                if(type !in listOf("text","thinking","server_tool_use","web_search_tool_result","web_fetch_tool_result")) return
                if(type=="text" && (block["text"] as? JsonPrimitive)?.isString != true) return
                if(type=="thinking" && (block["thinking"] as? JsonPrimitive)?.isString != true) return
                indexes[index]=message.blocks.size
                message=projected(message.copy(blocks=message.blocks+block))
            }
            "content_block_delta" -> {
                val wire=(event["index"] as? JsonPrimitive)?.intOrNull ?: return
                val index=indexes[wire] ?: return
                val delta=event["delta"] as? JsonObject ?: return
                val blocks=message.blocks.toMutableList(); val b=blocks[index]; val map=b.toMutableMap()
                val key=when(delta.str("type")) { "text_delta" -> if(b.str("type")=="text") "text" else null; "thinking_delta" -> if(b.str("type")=="thinking") "thinking" else null; "signature_delta" -> if(b.str("type")=="thinking") "signature" else null; else -> null }
                if(key!=null && (delta[key] as? JsonPrimitive)?.isString==true) map[key]=JsonPrimitive(b.str(key)+delta.str(key))
                if(delta.str("type")=="citations_delta" && b.str("type")=="text") { val citation=delta["citation"] as? JsonObject; if(citation!=null && citation.str("url").isNotEmpty()) map["citations"]=JsonArray((b["citations"] as? JsonArray).orEmpty()+citation) }
                blocks[index]=JsonObject(map);message=projected(message.copy(blocks=blocks))
            }
            "content_block_stop" -> (event["index"] as? JsonPrimitive)?.intOrNull?.let(indexes::remove)
            "message_delta" -> { val delta=event["delta"] as? JsonObject ?: return; val reason=delta.str("stop_reason"); if(reason in listOf("end_turn","max_tokens","tool_use","refusal")) message=message.copy(stopReason=reason,usage=event["usage"] as? JsonObject ?: message.usage,stopDetails=delta["stop_details"] as? JsonObject); delta.str("title").takeIf { it.isNotBlank() }?.let { title=it }; if(reason=="refusal") message=projected(message.copy(blocks=message.blocks.filter { it.str("type") !in listOf("text","thinking") })) }
        }
    }
    fun settle(status: String): ChatMessage { message=projected(message.copy(status=status,blocks=if(message.stopReason=="refusal") message.blocks.filter { it.str("type") !in listOf("text","thinking") } else message.blocks)); return message }
}

class DeepSeekClient(private val origin: String = "https://api.deepseek.com", private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build()) {
    fun messageCall(key: String, body: JsonObject): Call = client.newCall(Request.Builder().url("$origin/anthropic/v1/messages").header("x-api-key",key).header("Content-Type","application/json").header("Accept","text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType())).build())
    fun verifyCall(key: String): Call = client.newCall(Request.Builder().url("$origin/models").header("Authorization","Bearer $key").build())
    fun verify(call: Call): List<String> = call.execute().use { response ->
        if(!response.isSuccessful) throw responseError(response)
        val json=codec.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
        (json?.get("data") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("id")?.takeIf(String::isNotEmpty) }
    }
    fun stream(call: Call, consume: (JsonObject)->Unit) {
        call.execute().use { response ->
            if(!response.isSuccessful) throw responseError(response)
            if(!response.header("Content-Type").orEmpty().contains("text/event-stream",true)) throw ApiException("protocol","Expected text/event-stream",response.code)
            val body=response.body ?: throw ApiException("protocol","Empty response")
            val decoder=SseDecoder(); var stopped=false
            fun process(frames:List<SseFrame>) { for(frame in frames) {
                val payload=runCatching { codec.parseToJsonElement(frame.data) as? JsonObject }.getOrNull()
                val error=payload?.get("error") as? JsonObject
                if(error!=null) throw ApiException("stream",error.str("message"))
                if(frame.event=="error") throw ApiException("stream",frame.data.take(2048))
                if(payload==null) continue
                consume(payload)
                if(payload.str("type")=="message_stop") { stopped=true;return }
            } }
            InputStreamReader(body.byteStream(),Charsets.UTF_8).use { reader -> val chars=CharArray(4096); while(!stopped) { val n=reader.read(chars); if(n<0) break; process(decoder.push(String(chars,0,n))) }; if(!stopped) process(decoder.end()) }
            if(!stopped) throw ApiException("interrupted","Stream interrupted before message_stop")
        }
    }
    private fun responseError(response:Response):ApiException { val text=response.body?.string().orEmpty().take(4096); val message=runCatching { (codec.parseToJsonElement(text) as JsonObject)["error"] as? JsonObject }.getOrNull()?.str("message")?.takeIf { it.isNotEmpty() } ?: text.ifEmpty { "HTTP ${response.code}" }; return ApiException("http",message,response.code) }
}

internal fun requestBody(settings: Settings, messages: JsonArray, title: Boolean):JsonObject = buildJsonObject {
    put("model",settings.model); put("system","该助手为DeepSeek，由深度求索公司创造。"); put("messages",messages); put("stream",true)
    put("thinking",buildJsonObject { put("type",if(settings.thinking) "enabled" else "disabled") })
    put("tools",buildJsonArray { add(buildJsonObject { put("type","deepseek_read_url");put("name","deepseek_read_url") }); if(settings.search) add(buildJsonObject {put("type","deepseek_web_search");put("name","deepseek_web_search")}) })
    put("safety_check",buildJsonObject {put("level","high")}); put("title",buildJsonObject {put("enabled",title)})
}
