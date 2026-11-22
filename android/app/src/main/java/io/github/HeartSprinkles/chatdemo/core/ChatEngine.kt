package io.github.HeartSprinkles.chatdemo.core

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.Call

/** Main-thread command surface; blocking networking and Room operations always run on IO. */
class ChatEngine(context:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val db=ChatDatabase.open(context.applicationContext)
    private val dao=db.dao()
    private val secure=SecureKey(context.applicationContext)
    private val preferences=CorePreferences(context.applicationContext)
    private val api=DeepSeekClient()
    private val mutable=MutableStateFlow(AppState())
    val state:StateFlow<AppState> = mutable.asStateFlow()
    private val cache=mutableMapOf<String,Conversation>()
    private val saveMutex=Mutex()
    private val pending=mutableMapOf<String,Job>()
    private val deleting=mutableSetOf<String>()
    private var generation:Job?=null
    private var call:Call?=null
    private var key=""
    private var selectEpoch=0L
    private var closed=false
    init { scope.launch {
        try {
            var keyError:String?=null
            val loaded=withContext(Dispatchers.IO) { Triple(dao.summaries(),preferences.load(),runCatching {secure.read()}.getOrElse {keyError="Stored API key could not be unlocked; re-enter it in Settings";""}) }
            key=loaded.third
            mutable.value=mutable.value.copy(conversations=loaded.first.map { it.conversation().copy(draft="") },settings=loaded.second.first.copy(hasApiKey=key.isNotBlank()),newDraft=loaded.second.second,isLoading=false,error=keyError)
        } catch(e:Exception) { mutable.value=mutable.value.copy(isLoading=false,error="Cannot load local storage: ${e.message}") }
    } }
    private fun command(action:()->Unit) { if(!closed) scope.launch { if(!state.value.isLoading) action() } }
    private fun publish(c:Conversation, metadata:Boolean=true) {
        cache[c.id]=c
        val s=state.value
        val summaries=if(metadata) (s.conversations.filterNot {it.id==c.id}+c.copy(messages=emptyList())).sortedByDescending {it.updatedAt} else s.conversations
        val selected=cache[s.selectedId]
        val groups=selected?.messages?.groupBy {it.parentId}.orEmpty()
        val versions=groups.values.flatMap {siblings -> val v=siblings.map {MessageVersion(it.id,it.text,it.thinking,it.status)};siblings.map {it.id to v} }.toMap()
        mutable.value=s.copy(conversations=summaries,conversation=selected?.copy(messages=visibleMessages(selected).map {it.copy(versions=versions[it.id].orEmpty(),selectedVersionId=it.id)}),messageVersions=versions)
    }
    private fun persist(c:Conversation, immediate:Boolean=false) {
        if(c.id in deleting) return
        pending.remove(c.id)?.cancel()
        pending[c.id]=scope.launch {
            if(!immediate) delay(500)
            try { withContext(Dispatchers.IO) { saveMutex.withLock { dao.save(c) } } }
            catch(e:Exception) { mutable.value=state.value.copy(error="Cannot save conversation: ${e.message}") }
        }
    }
    private fun change(id:String, metadata:Boolean=true, immediate:Boolean=false, transform:(Conversation)->Conversation) {
        val c=cache[id] ?: return;val updated=transform(c);publish(updated,metadata);persist(updated,immediate)
    }
    fun clearError() { command { mutable.value=state.value.copy(error=null) } }
    fun updateSettings(settings:Settings, apiKey:String?=null) { command {
        if(settings.model !in listOf("deepseek-flash","deepseek-v4-pro")) { mutable.value=state.value.copy(error="Unsupported model");return@command }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { saveMutex.withLock { if(apiKey!=null) secure.save(apiKey.trim());preferences.save(settings) } }
                if(apiKey!=null) key=apiKey.trim()
                mutable.value=state.value.copy(settings=settings.copy(hasApiKey=key.isNotBlank()),error=null)
            } catch(e:Exception) { mutable.value=state.value.copy(error="Cannot save settings: ${e.message}") }
        }
    } }
    suspend fun validateApiKey(apiKey:String):Result<List<String>> = withContext(Dispatchers.IO) {
        if(apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key is required"))
        val verify=api.verifyCall(apiKey.trim())
        coroutineScope {
            val cancellationWatcher=launch(Dispatchers.Default) { try { awaitCancellation() } finally { verify.cancel() } }
            try { Result.success(api.verify(verify)) } catch(e:Exception) { if(!currentCoroutineContext().isActive) throw CancellationException("Key verification cancelled",e);Result.failure(e) } finally { cancellationWatcher.cancel() }
        }
    }
    suspend fun verifyKey(apiKey:String)=validateApiKey(apiKey)
    fun updateDraft(text:String) { command { val id=state.value.selectedId;if(id==null) {mutable.value=state.value.copy(newDraft=text);scope.launch {try {preferences.draft(text)} catch(e:Exception){mutable.value=state.value.copy(error="Cannot save draft")}}} else change(id) {it.copy(draft=text)} } }
    fun create() { command { selectEpoch++;mutable.value=state.value.copy(selectedId=null,conversation=null,newDraft="",messageVersions=emptyMap(),error=null) } }
    fun select(id:String) { command {
        if(state.value.conversations.none {it.id==id}) return@command
        val epoch=++selectEpoch
        mutable.value=state.value.copy(selectedId=id,conversation=null,error=null)
        if(cache[id]!=null) publish(cache.getValue(id),false) else scope.launch {
            try { val loaded=withContext(Dispatchers.IO) {dao.load(id)}; if(loaded!=null && epoch==selectEpoch) {cache.keys.filter {it!=state.value.activeConversationId && it!=id}.toList().forEach(cache::remove);publish(loaded,false)} }
            catch(e:Exception) {if(epoch==selectEpoch) mutable.value=state.value.copy(error="Cannot load conversation: ${e.message}")}
        }
    } }
    fun rename(id:String,title:String) { command {
        val c=cache[id]
        if(c!=null) change(id,immediate=true) {it.copy(title=title,manualTitle=true)}
        else scope.launch {try {val loaded=withContext(Dispatchers.IO) {dao.load(id)} ?: return@launch;publish(loaded.copy(title=title,manualTitle=true));persist(cache.getValue(id),true)} catch(e:Exception){mutable.value=state.value.copy(error=e.message)} }
    } }
    fun delete(id:String) { command {
        deleting.add(id)
        if(state.value.activeConversationId==id) stop()
        pending.remove(id)?.cancel()
        scope.launch {
            try {withContext(Dispatchers.IO) {saveMutex.withLock {dao.delete(id)}};cache.remove(id);val s=state.value;mutable.value=s.copy(conversations=s.conversations.filterNot {it.id==id},selectedId=if(s.selectedId==id)null else s.selectedId,conversation=if(s.selectedId==id)null else s.conversation);selectEpoch++}
            catch(e:Exception){mutable.value=state.value.copy(error="Cannot delete conversation: ${e.message}")}
        }
    } }
    fun clearAll() { command {
        val ids=state.value.conversations.map {it.id}.toSet();deleting.addAll(ids);call?.cancel();pending.values.forEach {it.cancel()};pending.clear();selectEpoch++
        scope.launch {
            try {withContext(Dispatchers.IO) {saveMutex.withLock {dao.clearAll();preferences.draft("")}};cache.clear();mutable.value=state.value.copy(conversations=emptyList(),selectedId=null,conversation=null,newDraft="",messageVersions=emptyMap())}
            catch(e:Exception) {deleting.removeAll(ids);mutable.value=state.value.copy(error="Cannot clear history: ${e.message}")}
        }
    } }
    fun send(text:String=state.value.draft) { command {
        if(!readyForGeneration() || text.isBlank()) return@command
        val c=state.value.selectedId?.let(cache::get) ?: Conversation(draft=state.value.newDraft).also {cache[it.id]=it;mutable.value=state.value.copy(selectedId=it.id,newDraft="")}
        val path=visibleMessages(c); val user=ChatMessage(role="user",text=text.trim());val reply=ChatMessage(role="assistant",status="streaming")
        val updated=appendBranch(c.copy(draft=""),path.lastOrNull()?.id,listOf(user,reply));publish(updated);persist(updated,true)
        generate(updated.id,reply,path,user,null)
    } }
    private fun readyForGeneration():Boolean { if(state.value.isStreaming || state.value.selectedId in deleting) return false;if(state.value.selectedId!=null && cache[state.value.selectedId]==null) {mutable.value=state.value.copy(error="Conversation is still loading");return false};if(key.isBlank()) {mutable.value=state.value.copy(error="Set your API key in Settings first");return false};return true }
    fun edit(messageId:String,text:String) { command {
        if(!readyForGeneration() || text.isBlank()) return@command
        val c=cache[state.value.selectedId] ?: return@command;val path=visibleMessages(c);val index=path.indexOfFirst {it.id==messageId && it.role=="user"};if(index<0)return@command
        val user=ChatMessage(role="user",text=text.trim());val reply=ChatMessage(role="assistant",status="streaming");val updated=appendBranch(c,path[index].parentId,listOf(user,reply));publish(updated);persist(updated,true);generate(c.id,reply,path.take(index),user,null)
    } }
    fun regenerate(messageId:String) { command {
        if(!readyForGeneration())return@command
        val c=cache[state.value.selectedId] ?: return@command;val path=visibleMessages(c);val index=path.indexOfFirst {it.id==messageId};if(index<0)return@command
        val userIndex=if(path[index].role=="user") index else index-1;val user=path.getOrNull(userIndex)?.takeIf {it.role=="user"} ?: return@command
        val reply=ChatMessage(role="assistant",status="streaming");val updated=appendBranch(c,user.id,listOf(reply));publish(updated);persist(updated,true);generate(c.id,reply,path.take(userIndex),user,null)
    } }
    fun selectVersion(messageId:String,versionId:String) { command {
        if(state.value.isStreaming)return@command
        val c=cache[state.value.selectedId] ?: return@command
        val original=c.messages.find {it.id==messageId} ?: return@command;val target=c.messages.find {it.id==versionId} ?: return@command
        if(original.parentId!=target.parentId)return@command
        val updated=selectBranch(c,versionId);publish(updated);persist(updated,true)
    } }
    fun continueReply() { command {
        if(!readyForGeneration())return@command
        val c=cache[state.value.selectedId] ?: return@command;val path=visibleMessages(c);val reply=path.lastOrNull()?.takeIf(::canContinueMessage) ?: return@command;val user=path.getOrNull(path.size-2)?.takeIf {it.role=="user"} ?: return@command
        generate(c.id,reply,path.dropLast(2),user,reply)
    } }
    private fun generate(id:String,reply:ChatMessage,history:List<ChatMessage>,user:ChatMessage,prefix:ChatMessage?) {
        val settings=state.value.settings;val titleAllowed=cache[id]?.manualTitle==false && history.isEmpty();val body=requestBody(settings,requestMessages(history,user,prefix,settings.thinking),titleAllowed)
        val accumulator=ReplyAccumulator(reply);mutable.value=state.value.copy(activeConversationId=id,error=null)
        val currentCall=api.messageCall(key,body);call=currentCall
        generation=scope.launch {
            var status="complete"
            try { withContext(Dispatchers.IO) {
                var lastPublish=0L
                api.stream(currentCall) { event ->
                    accumulator.apply(event)
                    val now=System.nanoTime()
                    if(now-lastPublish >= 75_000_000L || event.str("type")=="message_delta") {
                        lastPublish=now
                        val snapshot=accumulator.message
                        scope.launch {
                            if(call===currentCall && id !in deleting && cache.containsKey(id)) change(id,false) {c -> c.copy(messages=c.messages.map {if(it.id==reply.id) snapshot.copy(parentId=it.parentId,selectedChildId=it.selectedChildId) else it})}
                        }
                    }
                }
            } }
            catch(e:Exception) {status=if(currentCall.isCanceled() || e is CancellationException) "cancelled" else "error";if(status=="error") mutable.value=state.value.copy(error=e.message?.replace(key,"[redacted]") ?: "Network error")}
            finally {
                if(cache.containsKey(id) && id !in deleting) { val settled=accumulator.settle(status);change(id,metadata=true,immediate=true) {c -> c.copy(updatedAt=System.currentTimeMillis(),title=if(titleAllowed && !c.manualTitle) accumulator.title ?: c.title else c.title,messages=c.messages.map {if(it.id==reply.id) settled.copy(parentId=it.parentId,selectedChildId=it.selectedChildId) else it})} }
                if(call===currentCall) {call=null;generation=null;mutable.value=state.value.copy(activeConversationId=null)}
            }
        }
    }
    fun stop() { call?.cancel() }
    fun close() { if(closed)return;closed=true;call?.cancel();scope.launch {generation?.join();pending.values.toList().forEach {it.join()};preferences.close();db.close();scope.cancel()} }
}
