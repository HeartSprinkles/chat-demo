package io.github.HeartSprinkles.chatdemo.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

internal class CorePreferences(context:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={File(context.noBackupFilesDir,"chat-settings.preferences_pb")})
    private val settingsKey=stringPreferencesKey("settings")
    private val draftKey=stringPreferencesKey("new_draft")
    suspend fun load():Pair<Settings,String> { val prefs=store.data.first();return (prefs[settingsKey]?.let {codec.decodeFromString<Settings>(it)} ?: Settings()) to prefs[draftKey].orEmpty() }
    suspend fun save(settings:Settings) { store.edit {it[settingsKey]=codec.encodeToString(settings.copy(hasApiKey=false))} }
    suspend fun draft(text:String) {store.edit {it[draftKey]=text} }
    fun close() {scope.cancel()}
}
