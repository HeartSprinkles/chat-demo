package io.github.HeartSprinkles.chatdemo.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

internal class CorePreferences(context:Context) {
    private val store=sharedStore(File(context.noBackupFilesDir,"chat-settings.preferences_pb"))
    private val settingsKey=stringPreferencesKey("settings")
    private val draftKey=stringPreferencesKey("new_draft")
    suspend fun load():Pair<Settings,String> { val prefs=store.data.first();return (prefs[settingsKey]?.let {codec.decodeFromString<Settings>(it)} ?: Settings()) to prefs[draftKey].orEmpty() }
    suspend fun save(settings:Settings) { store.edit {it[settingsKey]=codec.encodeToString(settings.copy(hasApiKey=false))} }
    suspend fun draft(text:String) {store.edit {it[draftKey]=text} }
    // DataStore lives for the process, not an Activity/ViewModel instance.
    fun close() = Unit
    companion object {
        private val stores=mutableMapOf<String,DataStore<Preferences>>()
        @Synchronized private fun sharedStore(file:File):DataStore<Preferences> {
            val target=file.canonicalFile
            return stores.getOrPut(target.absolutePath) {
                PreferenceDataStoreFactory.create(scope=CoroutineScope(SupervisorJob()+Dispatchers.IO),produceFile={target})
            }
        }
    }
}
