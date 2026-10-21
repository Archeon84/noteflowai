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

    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[/\\\\]"), "_")
            .replace("..", "_")
            .take(150)
    }

    private fun isWithinDirectory(file: File, dir: File): Boolean {
        val dirPath = dir.canonicalPath
        val filePath = file.canonicalPath
        return filePath == dirPath || filePath.startsWith(dirPath + File.separator)
    }

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
            val safeId = sanitizeId(session.id)
            val file = File(chatsDir, "$safeId.json")
            if (!isWithinDirectory(file, chatsDir)) return@withContext
            val json = gson.toJson(session)
            com.noteflowai.app.data.AtomicJsonFile.write(file, json)
            refresh()
        }
    }

    suspend fun deleteSession(id: String) {
        withContext(Dispatchers.IO) {
            val safeId = sanitizeId(id)
            val file = File(chatsDir, "$safeId.json")
            if (!isWithinDirectory(file, chatsDir)) return@withContext
            file.delete()
            refresh()
        }
    }

    suspend fun getSession(id: String): ChatSession? {
        return withContext(Dispatchers.IO) {
            val safeId = sanitizeId(id)
            val file = File(chatsDir, "$safeId.json")
            if (!isWithinDirectory(file, chatsDir)) return@withContext null
            if (file.exists()) {
                try {
                    gson.fromJson(file.readText(), ChatSession::class.java)
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun getInstance(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
