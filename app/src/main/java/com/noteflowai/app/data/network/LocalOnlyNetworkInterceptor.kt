package com.noteflowai.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor that enforces hard network isolation when Local-Only mode is active.
 * Permits only local loopback endpoints (127.0.0.1, localhost, 10.0.2.2) for on-device/local servers.
 */
class LocalOnlyNetworkInterceptor(
    private val isLocalOnlyProvider: () -> Boolean
) : Interceptor {

    companion object {
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val isLocalOnly = isLocalOnlyProvider()
        val request = chain.request()
        val host = request.url.host.lowercase()

        if (isLocalOnly && !LOCAL_HOSTS.contains(host)) {
            throw LocalOnlyBlockedException(host)
        }

        return chain.proceed(request)
    }
}
