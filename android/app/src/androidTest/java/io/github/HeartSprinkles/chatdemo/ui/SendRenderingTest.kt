package io.github.HeartSprinkles.chatdemo.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.HeartSprinkles.chatdemo.ChatScreen
import io.github.HeartSprinkles.chatdemo.ChatViewModel
import io.github.HeartSprinkles.chatdemo.MessageCard
import io.github.HeartSprinkles.chatdemo.core.ChatEngine
import io.github.HeartSprinkles.chatdemo.core.ChatMessage
import io.github.HeartSprinkles.chatdemo.core.DeepSeekClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Local intercepted HTTPS response: exercises the real button/engine without credentials/network. */
class SendRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val markdown = "# Answer\n\n**Native** markdown\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```kotlin\nval answer = 42\n```\n\n[Source](https://example.com)"

    @Test fun welcomeSendInsertsUserAndAssistantAndRendersMarkdown() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(application.cacheDir, "send-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getDatabasePath(name: String): File = File(directory, name)
        }
        val jsonText = kotlinx.serialization.json.JsonPrimitive(markdown).toString()
        val sse = "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":$jsonText}}\n\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream").body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val engine = ChatEngine(isolated, DeepSeekClient(client = client))
        val model = ChatViewModel(application, engine)
        try {
            compose.setContent { MaterialTheme { ChatScreen(model, false) } }
            compose.waitUntil(10_000) { !engine.state.value.isLoading }
            compose.runOnIdle { engine.updateSettings(engine.state.value.settings, "test-only-key") }
            compose.waitUntil(10_000) { engine.state.value.settings.hasApiKey }
            compose.onNodeWithTag("chat-draft").performTextInput("First message")
            compose.waitUntil(10_000) { engine.state.value.draft == "First message" }
            compose.onNodeWithTag("chat-send").performClick()
            compose.waitUntil(15_000) {
                val state = engine.state.value
                state.messages.size == 2 && state.messages.last().text == markdown && !state.isStreaming
            }
            compose.onNodeWithText("Assistant").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("markdown-ready").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Copy code 1 · kotlin").assertExists()
        } finally { compose.runOnIdle { engine.close() } }
    }

    @Test fun emptyStreamingMessageThenComplexContentDoesNotCrash() {
        val answer = mutableStateOf(ChatMessage(id = "answer", role = "assistant", status = "streaming"))
        compose.setContent {
            MaterialTheme {
                Column {
                    MessageCard(ChatMessage(id = "user", role = "user", text = "Hello"), false, false, {}, {}, {})
                    MessageCard(answer.value, false, false, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Assistant").assertIsDisplayed()
        compose.runOnIdle { answer.value = answer.value.copy(text = markdown, status = "complete") }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("markdown-ready").fetchSemanticsNodes().size >= 2 }
            compose.onNodeWithText("Copy code 1 · kotlin").assertExists()
    }
}
