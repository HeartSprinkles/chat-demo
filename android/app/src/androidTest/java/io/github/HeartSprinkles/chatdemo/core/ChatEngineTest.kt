package io.github.HeartSprinkles.chatdemo.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ChatEngineTest {
    private fun isolatedContext():Context {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val directory=File(base.noBackupFilesDir,"engine-test-${UUID.randomUUID()}").apply {mkdirs()}
        return object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File=directory
            override fun getDatabasePath(name:String):File=File(directory,name)
        }
    }
    private suspend fun awaitState(engine:ChatEngine, predicate:(AppState)->Boolean):AppState = withTimeout(15_000) {engine.state.first(predicate)}
    private fun fakeApi(count:AtomicInteger):DeepSeekClient {
        val client=OkHttpClient.Builder().addInterceptor {chain ->
            count.incrementAndGet()
            val sse=listOf(
                """{"type":"message_start","message":{"id":"fake","model":"deepseek-flash"}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Fake answer"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" completed"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn","title":"Fake title"}}""",
                """{"type":"message_stop"}"""
            ).joinToString("") {"data: $it\n\n"}
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type","text/event-stream").body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return DeepSeekClient(client=client)
    }
    @Test fun welcomeSendWithoutKeyReturnsSettingsErrorWithoutNullSetCrash() = runBlocking {
        val engine=ChatEngine(isolatedContext())
        try {
            awaitState(engine) {!it.isLoading}
            assertNull(engine.state.value.selectedId)
            engine.send("hello from welcome")
            val state=awaitState(engine) {it.error?.contains("API key")==true}
            assertNull(state.selectedId);assertTrue(state.messages.isEmpty());assertFalse(state.isStreaming)
        } finally {withContext(Dispatchers.Main) {engine.close()} }
    }
    @Test fun welcomeAndSelectedConversationSendCompleteThroughRealEngine() = runBlocking {
        val count=AtomicInteger()
        val engine=ChatEngine(isolatedContext(),fakeApi(count))
        try {
            awaitState(engine) {!it.isLoading}
            engine.updateSettings(Settings(thinking=true),"test-fake-key")
            awaitState(engine) {it.settings.hasApiKey}
            engine.updateDraft("first user message")
            awaitState(engine) {it.draft=="first user message"}
            assertNull(engine.state.value.selectedId)
            engine.send()
            val first=awaitState(engine) {it.messages.size==2 && it.messages.last().status=="complete" && !it.isStreaming}
            assertNotNull(first.selectedId);assertEquals("first user message",first.messages.first().text)
            assertEquals("Fake answer completed",first.messages.last().text);assertEquals("Fake title",first.conversation?.title)
            assertEquals("",first.draft);assertEquals(1,count.get())
            engine.send("second user message")
            val second=awaitState(engine) {it.messages.size==4 && it.messages.last().status=="complete" && !it.isStreaming}
            assertEquals(first.selectedId,second.selectedId)
            assertEquals("second user message",second.messages[2].text)
            assertEquals("Fake answer completed",second.messages.last().text);assertEquals(2,count.get())
        } finally {withContext(Dispatchers.Main) {engine.close()} }
    }
}
