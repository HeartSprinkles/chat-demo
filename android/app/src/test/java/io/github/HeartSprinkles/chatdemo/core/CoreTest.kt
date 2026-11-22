package io.github.HeartSprinkles.chatdemo.core

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun json(value:String)=codec.parseToJsonElement(value).jsonObject
    private fun answer(text:String,status:String="complete",reason:String="end_turn",extra:List<JsonObject> = emptyList()) = projected(ChatMessage(role="assistant",status=status,stopReason=reason,blocks=listOf(json("""{"type":"text","text":"$text"}"""))+extra))
    @Test fun welcomeConversationMembershipNeverPassesNullToConcurrentSet() {
        val deleting=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        deleting.add("deleted")
        assertFalse(containsConversation(deleting,null))
        assertFalse(containsConversation(deleting,"active"))
        assertTrue(containsConversation(deleting,"deleted"))
    }
    @Test fun malformedCredentialsAreRejectedWithoutConstructingHttpHeaders() {
        assertTrue(isSafeApiKey("sk-example-123"))
        assertFalse(isSafeApiKey(""));assertFalse(isSafeApiKey("sk-first\nsecond"))
        assertFalse(isSafeApiKey("sk-钥匙"));assertFalse(isSafeApiKey("sk-space key"));assertFalse(isSafeApiKey("sk-\u0000key"))
    }
    @Test fun framingHandlesCrLfSplitsCommentsBOMMultilineAndId() {
        val d=SseDecoder()
        assertTrue(d.push("\uFEFF: ping\r\nid: 42\r\nevent: delta\r\ndata: one\r").isEmpty())
        val frames=d.push("\ndata: two\r\n\r\ndata:\n\n")
        assertEquals(listOf(SseFrame("delta","one\ntwo","42"),SseFrame("message","","42")),frames)
        assertTrue(d.push("data: unfinished").isEmpty());assertTrue(d.end().isEmpty())
    }
    @Test fun loneCrAndPersistentId() {
        val d=SseDecoder();assertEquals(1,d.push("id: a\rdata: x\r\rdata: y\r").size)
        assertEquals(SseFrame("message","y","a"),d.push("\rx").single())
        assertTrue(d.end().isEmpty())
    }
    @Test fun branchesRetainOriginalDescendantsAndRestoreSelectedPath() {
        val u=ChatMessage(id="u",role="user",text="first")
        val a=answer("old").copy(id="a")
        val tail=ChatMessage(id="tail",role="user",text="followup")
        val c=appendBranch(Conversation(id="c"),null,listOf(u,a,tail))
        val edited=appendBranch(c,null,listOf(ChatMessage(id="u2",role="user",text="edited"),answer("new").copy(id="a2")))
        assertEquals(listOf("u2","a2"),visibleMessages(edited).map {it.id})
        assertEquals(listOf("u","a","tail"),visibleMessages(selectBranch(edited,"u")).map {it.id})
        assertEquals(5,edited.messages.size)
    }
    @Test fun thousandMessageBranchRestoresEntireOriginalPath() {
        val nodes=(0 until 1000).map {ChatMessage(id="m$it",role=if(it%2==0) "user" else "assistant",text="message $it")}
        val original=appendBranch(Conversation(id="large"),null,nodes)
        val branched=appendBranch(original,"m498",listOf(ChatMessage(id="alternate",role="assistant",text="alternate reply")))
        assertEquals(500,visibleMessages(branched).size)
        val restored=selectBranch(branched,"m499")
        assertEquals(nodes.map {it.id},visibleMessages(restored).map {it.id})
        assertEquals(1001,restored.messages.size)
    }
    @Test fun cycleDoesNotLoop() {
        val c=Conversation(rootId="a",messages=listOf(ChatMessage(id="a",role="user",selectedChildId="b"),ChatMessage(id="b",role="assistant",selectedChildId="a")))
        assertEquals(2,visibleMessages(c).size)
    }
    @Test fun historyExcludesThinkingSearchAndRetainsSignedReadLink() {
        val thinking=json("""{"type":"thinking","thinking":"secret","signature":"sig"}""")
        val search=json("""{"type":"server_tool_use","kind":"search","id":"search","name":"deepseek_web_search","input":{"action":"search","queries":["q"]}}""")
        val read=json("""{"type":"server_tool_use","kind":"read_link","id":"read","name":"deepseek_read_url","caller":{"type":"direct"},"input":{"action":"read_link","url":"https://example.com"}}""")
        val result=json("""{"type":"web_fetch_tool_result","kind":"read_link","tool_use_id":"read","signature":"signed-page","content":{"type":"web_fetch_result","url":"https://example.com"}}""")
        val m=answer("hello",extra=listOf(thinking,search,read,result))
        val context=assistantContext(m)
        assertFalse(context.toString().contains("secret"));assertFalse(context.toString().contains("deepseek_web_search"))
        assertTrue(context.toString().contains("signed-page"));assertFalse(context.toString().contains("caller"))
        val unsigned=m.copy(blocks=m.blocks.map {if(it.str("type")=="web_fetch_tool_result") JsonObject(it.filterKeys {k->k!="signature"}) else it})
        assertEquals(listOf("text"),assistantContext(unsigned).map {it.jsonObject.str("type")})
    }
    @Test fun contextIncludesOnlyAnsweredPairs() {
        val user=ChatMessage(role="user",text="old")
        val error=answer("partial","error")
        val next=ChatMessage(role="user",text="new")
        assertEquals(1,requestMessages(listOf(user,error),next).size)
        assertEquals(3,requestMessages(listOf(user,answer("yes")),next).size)
        assertEquals(1,requestMessages(listOf(user,answer("no",reason="refusal")),next).size)
    }
    @Test fun refusalClearsTextAndThinkingButKeepsTools() {
        val accumulator=ReplyAccumulator(ChatMessage(role="assistant"))
        accumulator.apply(json("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"unsafe"}}"""))
        accumulator.apply(json("""{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":"unsafe reasoning"}}"""))
        accumulator.apply(json("""{"type":"content_block_start","index":2,"content_block":{"type":"server_tool_use","id":"t","kind":"read_link","name":"deepseek_read_url","input":{"action":"read_link","url":"https://example.com"}}}"""))
        accumulator.apply(json("""{"type":"message_delta","delta":{"stop_reason":"refusal"}}"""))
        val settled=accumulator.settle("complete")
        assertEquals("",settled.text);assertEquals("",settled.thinking);assertEquals(1,settled.tools.size);assertFalse(canContinueMessage(settled))
    }
    @Test fun continuationRejectsErrorRefusalToolsAndAllowsCancelledAndMaxTokens() {
        assertTrue(canContinueMessage(answer("partial","cancelled")))
        assertTrue(canContinueMessage(answer("partial",reason="max_tokens")))
        assertFalse(canContinueMessage(answer("partial","error")))
        assertFalse(canContinueMessage(answer("partial",reason="refusal")))
        assertFalse(canContinueMessage(answer("partial","cancelled",extra=listOf(json("""{"type":"web_fetch_tool_result","kind":"read_link"}""")))))
        assertFalse(canContinueMessage(answer("partial","cancelled").copy(prefix=false)))
    }
    @Test fun indexesMapIgnoredBlocksWithoutCorruptingReply() {
        val a=ReplyAccumulator(ChatMessage(role="assistant"))
        a.apply(json("""{"type":"content_block_start","index":0,"content_block":{"type":"unknown"}}"""))
        a.apply(json("""{"type":"content_block_start","index":4,"content_block":{"type":"text","text":"a"}}"""))
        a.apply(json("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"bad"}}"""))
        a.apply(json("""{"type":"content_block_delta","index":4,"delta":{"type":"text_delta","text":"b"}}"""))
        assertEquals("ab",a.message.text)
    }
    @Test fun networkRequiresMessageStopAndUsesApprovedRequestHeaders() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"type\":\"message_stop\"}\n\n"))
            val client=DeepSeekClient(server.url("/").toString().trimEnd('/'))
            val body=requestBody(Settings(thinking=true,search=true),JsonArray(emptyList()),true)
            val events=mutableListOf<JsonObject>();client.stream(client.messageCall("test-key",body),events::add)
            assertEquals("message_stop",events.single().str("type"))
            val request=server.takeRequest();assertEquals("/anthropic/v1/messages",request.path);assertEquals("test-key",request.getHeader("x-api-key"));assertEquals("text/event-stream",request.getHeader("Accept"))
            val sent=json(request.body.readUtf8());assertEquals("high",sent["safety_check"]!!.jsonObject.str("level"));assertEquals(2,sent["tools"]!!.jsonArray.size)
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"type\":\"ping\"}\n\n"))
            try {client.stream(client.messageCall("test-key",body)) {};fail("Must reject EOF")} catch(e:ApiException) {assertEquals("interrupted",e.kind)}
        } finally {server.shutdown()}
    }
    @Test fun streamErrorFrameAndNonSseAreErrors() {
        val server=MockWebServer();server.start()
        try {
            val client=DeepSeekClient(server.url("/").toString().trimEnd('/'));val body=requestBody(Settings(),JsonArray(emptyList()),false)
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"error\":{\"message\":\"rate limit\"}}\n\n"))
            try {client.stream(client.messageCall("k",body)) {};fail("Must fail")} catch(e:ApiException) {assertEquals("stream",e.kind);assertEquals("rate limit",e.message)}
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("{}"))
            try {client.stream(client.messageCall("k",body)) {};fail("Must fail")} catch(e:ApiException) {assertEquals("protocol",e.kind)}
        } finally {server.shutdown()}
    }
}
