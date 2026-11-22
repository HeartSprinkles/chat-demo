package io.github.HeartSprinkles.chatdemo.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName="sessions") internal data class SessionRow(@PrimaryKey val id:String, val title:String, val draft:String, val updatedAt:Long, val rootId:String?, val manualTitle:Boolean)
@Entity(tableName="messages", primaryKeys=["sessionId","id"], foreignKeys=[ForeignKey(entity=SessionRow::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)], indices=[Index("sessionId")]) internal data class MessageRow(val sessionId:String,val id:String,val position:Int,val json:String)
@Entity(tableName="preferences") internal data class PreferenceRow(@PrimaryKey val id:Int=1,val json:String)
@Dao internal abstract class ChatDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") abstract suspend fun summaries():List<SessionRow>
    @Query("SELECT * FROM sessions WHERE id=:id") abstract suspend fun session(id:String):SessionRow?
    @Query("SELECT * FROM messages WHERE sessionId=:id ORDER BY position") abstract suspend fun messages(id:String):List<MessageRow>
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putSession(row:SessionRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putMessages(rows:List<MessageRow>)
    @Query("DELETE FROM messages WHERE sessionId=:id") abstract suspend fun clearMessages(id:String)
    @Query("DELETE FROM sessions WHERE id=:id") abstract suspend fun delete(id:String)
    @Query("DELETE FROM sessions") abstract suspend fun clearAll()
    @Query("SELECT * FROM preferences WHERE id=1") abstract suspend fun preferences():PreferenceRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putPreferences(row:PreferenceRow)
    @Transaction open suspend fun save(c:Conversation) { putSession(SessionRow(c.id,c.title,c.draft,c.updatedAt,c.rootId,c.manualTitle));clearMessages(c.id);putMessages(c.messages.mapIndexed { i,m->MessageRow(c.id,m.id,i,codec.encodeToString(m)) }) }
    @Transaction open suspend fun load(id:String):Conversation? { val row=session(id) ?: return null;return row.conversation(messages(id).map { codec.decodeFromString<ChatMessage>(it.json).let { m -> if(m.status=="streaming") projected(m.copy(status="cancelled")) else m } }) }
}
internal fun SessionRow.conversation(messages:List<ChatMessage> = emptyList())=Conversation(id,title,draft,messages,updatedAt,rootId,manualTitle)
@Database(entities=[SessionRow::class,MessageRow::class,PreferenceRow::class],version=1,exportSchema=true) internal abstract class ChatDatabase:RoomDatabase() {
    abstract fun dao():ChatDao
    companion object { fun open(context:Context):ChatDatabase = Room.databaseBuilder(context,ChatDatabase::class.java,File(context.noBackupFilesDir,"chat-core.db").absolutePath).build() }
}

/** Only ciphertext is stored, inside Android's backup-excluded directory. No key logging/export. */
internal class SecureKey(context:Context) {
    private val file=File(context.noBackupFilesDir,"deepseek-key.enc")
    private val alias="chat-demo.deepseek.aes.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun read():String {
        if(!file.exists()) return ""
        val bytes=file.readBytes(); require(bytes.size>13) { "Invalid encrypted key" }
        val ivLength=bytes[0].toInt() and 255; require(ivLength in 12..16 && bytes.size>ivLength+1)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,1+ivLength)))
        return String(cipher.doFinal(bytes.copyOfRange(1+ivLength,bytes.size)),Charsets.UTF_8)
    }
    @Synchronized fun save(value:String) {
        if(value.isBlank()) { if(file.exists() && !file.delete()) error("Cannot remove encrypted key"); return }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val bytes=byteArrayOf(cipher.iv.size.toByte())+cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val atomic=android.util.AtomicFile(file);val stream=atomic.startWrite();try {stream.write(bytes);atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
    }
}
