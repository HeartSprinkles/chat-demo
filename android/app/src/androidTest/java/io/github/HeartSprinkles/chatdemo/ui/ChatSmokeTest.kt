package io.github.HeartSprinkles.chatdemo.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import io.github.HeartSprinkles.chatdemo.MainActivity
import org.junit.Rule
import org.junit.Test

/** No credentials or network are required. Supports either device language. */
class ChatSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun settingsAndLanguageAreNative() {
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[io.github.HeartSprinkles.chatdemo.ChatViewModel::class.java]
        compose.waitUntil(10_000) { !model.state.value.isLoading }
        val locale = model.state.value.settings.locale
        val initialChinese = locale == "zh" || (locale == "system" && java.util.Locale.getDefault().language == "zh")
        compose.waitUntil(10_000) { exists(if (initialChinese) "设置" else "Settings") }
        compose.onAllNodesWithText(if (initialChinese) "设置" else "Settings")[0].performClick()
        compose.onNodeWithText("English").assertIsDisplayed().performClick()
        compose.onNodeWithText(if (initialChinese) "保存" else "Save").performClick()
        compose.waitUntil(10_000) { exists("Settings") && !exists("Save") }
        compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("中文").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { exists("设置") && !exists("保存") }
        compose.onNodeWithText("设置").assertIsDisplayed()
    }
}
