package io.github.HeartSprinkles.chatdemo.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import io.github.HeartSprinkles.chatdemo.MainActivity
import org.junit.Rule
import org.junit.Test

/** No credentials or network are required. Supports either device language. */
class ChatSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun settingsAndLanguageAreNative() {
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[io.github.HeartSprinkles.chatdemo.ChatViewModel::class.java]
        compose.waitUntil(10_000) { !model.state.value.isLoading && exists("toolbar-settings") }
        compose.onNodeWithTag("toolbar-settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-dialog").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-save").performClick()
        compose.waitUntil(10_000) { model.preferences.value.language == "en" && !exists("settings-save") }
        compose.onNodeWithTag("toolbar-settings").assertIsDisplayed().assertTextContains("Settings").performClick()
        compose.onNodeWithText("中文").performClick()
        compose.onNodeWithTag("settings-save").performClick()
        compose.waitUntil(10_000) { model.preferences.value.language == "zh" && !exists("settings-save") }
        compose.onNodeWithTag("toolbar-settings").assertIsDisplayed().assertTextContains("设置")
    }
}
