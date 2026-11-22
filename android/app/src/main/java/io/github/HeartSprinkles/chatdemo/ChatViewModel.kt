package io.github.HeartSprinkles.chatdemo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.HeartSprinkles.chatdemo.core.ChatEngine
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

data class UiPreferences(val language: String = "zh", val theme: String = "system")

class ChatViewModel @JvmOverloads constructor(application: Application, val engine: ChatEngine = ChatEngine(application)) : AndroidViewModel(application) {
    val state = engine.state
    val preferences = state.map { value ->
        UiPreferences(if (value.settings.locale == "system") {
            if (java.util.Locale.getDefault().language == "zh") "zh" else "en"
        } else value.settings.locale, value.settings.theme)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiPreferences())
    override fun onCleared() { engine.close() }
}

internal fun label(zh: Boolean, chinese: String, english: String) = if (zh) chinese else english
infix fun String.en(english: String) = Pair(this, english)
internal fun Pair<String, String>.local(zh: Boolean) = if (zh) first else second
