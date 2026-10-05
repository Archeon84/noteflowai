package com.noteflowai.app.data.network

import okhttp3.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LocalOnlyNetworkInterceptorTest {

    private fun createChain(url: String, interceptor: LocalOnlyNetworkInterceptor): Response {
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(null, "success"))
                    .build()
            })
            .build()
        return client.newCall(request).execute()
    }

    @Test
    fun `external cloud api is blocked when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        assertThrows(LocalOnlyBlockedException::class.java) {
            createChain("https://api.openai.com/v1/chat/completions", interceptor)
        }
    }

    @Test
    fun `anthropic and deepgram endpoints are blocked when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        assertThrows(LocalOnlyBlockedException::class.java) {
            createChain("https://api.anthropic.com/v1/messages", interceptor)
        }
        assertThrows(LocalOnlyBlockedException::class.java) {
            createChain("https://api.deepgram.com/v1/speak", interceptor)
        }
    }

    @Test
    fun `local loopback host 10_0_2_2 is permitted even when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        val response = createChain("http://10.0.2.2:11434/api/chat", interceptor)
        assertEquals(200, response.code)
    }

    @Test
    fun `local localhost and 127_0_0_1 are permitted when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        val response1 = createChain("http://127.0.0.1:11434/api/tags", interceptor)
        val response2 = createChain("http://localhost:8080/completion", interceptor)
        assertEquals(200, response1.code)
        assertEquals(200, response2.code)
    }

    @Test
    fun `all endpoints are permitted when local-only mode is disabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { false })
        val response = createChain("https://api.openai.com/v1/chat/completions", interceptor)
        assertEquals(200, response.code)
    }
}
