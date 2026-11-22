package io.github.HeartSprinkles.chatdemo.core

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun roomRoundTripPreservesBranchDraftAndRecoversInterruptedReply() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build()
        try {
            val user=ChatMessage(id="user",role="user",text="question")
            val first=projected(ChatMessage(id="first",role="assistant",status="streaming",blocks=listOf(buildJsonObject {put("type","text");put("text","partial")})))
            val original=appendBranch(Conversation(id="test",title="Room roundtrip",draft="unsent"),null,listOf(user,first))
            val branched=appendBranch(original,"user",listOf(ChatMessage(id="second",role="assistant",text="alternate")))
            db.dao().save(branched)
            val loaded=db.dao().load("test")!!
            assertEquals("unsent",loaded.draft);assertEquals(3,loaded.messages.size)
            assertEquals(listOf("user","second"),visibleMessages(loaded).map {it.id})
            assertEquals("cancelled",loaded.messages.first {it.id=="first"}.status)
            assertEquals("partial",loaded.messages.first {it.id=="first"}.text)
            assertEquals(listOf("user","first"),visibleMessages(selectBranch(loaded,"first")).map {it.id})
            db.dao().delete("test")
            assertNull(db.dao().load("test"));assertTrue(db.dao().messages("test").isEmpty())
        } finally {db.close()}
    }
    @Test fun clearAllDeletesMessagesAndDrafts() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build()
        try {
            db.dao().save(appendBranch(Conversation(id="a",draft="draft-a"),null,listOf(ChatMessage(role="user",text="one"))))
            db.dao().save(Conversation(id="b",draft="draft-b"))
            assertEquals(2,db.dao().summaries().size)
            db.dao().clearAll()
            assertTrue(db.dao().summaries().isEmpty());assertTrue(db.dao().messages("a").isEmpty())
        } finally {db.close()}
    }
    @Test fun secureKeyEncryptsRoundTripsAndClearsWithoutPlaintextBackup() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val directory=java.io.File(base.noBackupFilesDir,"core-key-test-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        val context=object:android.content.ContextWrapper(base) {override fun getNoBackupFilesDir():java.io.File=directory}
        val secure=SecureKey(context)
        secure.save("instrumentation-test-key-not-a-real-credential")
        try {
            assertEquals("instrumentation-test-key-not-a-real-credential",secure.read())
            val file=java.io.File(context.noBackupFilesDir,"deepseek-key.enc")
            assertTrue(file.isFile)
            assertFalse(String(file.readBytes(),Charsets.ISO_8859_1).contains("instrumentation-test-key-not-a-real-credential"))
            secure.save("");assertEquals("",secure.read());assertFalse(file.exists())
        } finally {secure.save("")}
    }
}
