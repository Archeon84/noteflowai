package com.noteflowai.app.data.chat

import retrofit2.http.*

interface AiApiService {
    @POST("{path}")
    suspend fun chat(
        @Path("path", encoded = true) path: String,
        @HeaderMap headers: Map<String, String>,
        @Body request: ChatRequest
    ): ChatResponse

    @GET("{path}")
    suspend fun getModels(
        @Path("path", encoded = true) path: String,
        @HeaderMap headers: Map<String, String>
    ): Map<String, Any>
}
