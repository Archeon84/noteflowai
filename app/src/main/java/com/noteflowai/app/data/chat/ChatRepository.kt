package com.noteflowai.app.data.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class ChatRepository(context: Context) {
    private val chatsDir = File(context.filesDir, "chats").also { it.mkdirs() }
    private val gson = Gson()

    private val _sessionsFlow = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessionsFlow: StateFlow<List<ChatSession>> = _sessionsFlow.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val files = chatsDir.listFiles { it.extension == "json" } ?: emptyArray()
        val sessions = files.mapNotNull { file ->
            try {
                file.readText().let { gson.fromJson(it, ChatSession::class.java) }
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.lastModified }
        _sessionsFlow.value = sessions
    }

    suspend fun saveSession(session: ChatSession) {
        withContext(Dispatchers.IO) {
            val file = File(chatsDir, "${session.id}.json")
            val json = gson.toJson(session)
            file.writeText(json)
            refresh()
        }
    }

    suspend fun deleteSession(id: String) {
        withContext(Dispatchers.IO) {
            File(chatsDir, "$id.json").delete()
            refresh()
        }
    }

    suspend fun getSession(id: String): ChatSession? {
        return withContext(Dispatchers.IO) {
            val file = File(chatsDir, "$id.json")
            if (file.exists()) {
                try {
                    gson.fromJson(file.readText(), ChatSession::class.java)
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }
}
