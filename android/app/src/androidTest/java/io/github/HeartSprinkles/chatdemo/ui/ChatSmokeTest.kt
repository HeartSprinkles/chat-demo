package io.github.HeartSprinkles.chatdemo.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import io.github.HeartSprinkles.chatdemo.MainActivity
import org.junit.Rule
import org.junit.Test

/** Run on a clean emulator/device. No credentials or network are required. */
class ChatSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsAndLanguageAreNative() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("API 密钥").assertIsDisplayed()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        // Restore default so this test is repeatable.
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("中文").performClick()
        compose.onNodeWithText("Save").performClick()
    }
}
