package com.noteflowai.app.data.chat

/**
 * User-selected recall scope in AI Chat:
 * - [NOTES_ONLY]: RAG is active. System queries notes and memory layer, injects grounded evidence, and generates citations.
 * - [REMOTE_API_ONLY]: RAG is bypassed. No private notes or memory segments are searched or injected into the prompt.
 *
 * Both modes can be executed with either the Offline On-Device LLM or a Remote Cloud API LLM.
 */
enum class ChatRecallMode {
    NOTES_ONLY,
    REMOTE_API_ONLY;

    companion object {
        fun fromString(value: String?): ChatRecallMode {
            return when (value?.uppercase()) {
                "REMOTE_API_ONLY" -> REMOTE_API_ONLY
                else -> NOTES_ONLY
            }
        }
    }
}
