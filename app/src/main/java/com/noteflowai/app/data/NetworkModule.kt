package com.noteflowai.app.data

import com.noteflowai.app.data.network.LocalOnlyBlockedException
import com.noteflowai.app.data.network.LocalOnlyNetworkInterceptor
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttpClient base. Each repository should call [newClientBuilder] to get a
 * builder pre-configured with the shared connection pool and base timeouts, then
 * apply repo-specific overrides (readTimeout, interceptors, etc.).
 *
 * Sharing one connection pool avoids the overhead of 4 independent pools
 * (each with its own connection keeper threads and socket allocations).
 */
object NetworkModule {
    private const val CONNECT_TIMEOUT_SEC = 30L

    private val sharedPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    @Volatile
    private var localOnlyChecker: (() -> Boolean)? = null

    fun setLocalOnlyChecker(checker: () -> Boolean) {
        localOnlyChecker = checker
    }

    /**
     * Returns a new [OkHttpClient.Builder] pre-seeded with the shared connection pool,
     * base connect timeout, and Local-Only Mode firewall interceptor (if configured).
     */
    fun newClientBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectionPool(sharedPool)

        firewallInterceptor()?.let { builder.addInterceptor(it) }

        return builder
    }

    /**
     * Standalone firewall interceptor for clients that cannot use
     * [newClientBuilder] (e.g. HTTP/1.1-only transports with their own pool).
     * Returns null when no checker is configured.
     */
    fun firewallInterceptor(): Interceptor? {
        val checker = localOnlyChecker ?: return null
        return LocalOnlyNetworkInterceptor(checker)
    }

    /** Loopback hosts permitted while Local-Only Mode is active. */
    private val localHosts = setOf("127.0.0.1", "localhost", "10.0.2.2")

    /**
     * Returns true when [urlString] targets a loopback host (stays on-device).
     * Services that read Local-Only Mode from DataStore (where the OkHttp
     * firewall checker may not be installed yet, e.g. background workers) use
     * this with their own flag to fail closed before building a request.
     */
    fun isLoopbackUrl(urlString: String): Boolean {
        val host = try {
            java.net.URL(urlString).host.lowercase()
        } catch (e: Exception) {
            return false
        }
        return localHosts.contains(host)
    }

    /** Returns true when Local-Only Mode is currently active. */
    fun isLocalOnly(): Boolean = localOnlyChecker?.invoke() ?: false

    /**
     * Guard for transports that bypass OkHttp (HttpURLConnection, Drive SDK).
     * Throws [LocalOnlyBlockedException] when Local-Only Mode is active and
     * the URL targets a non-loopback host. Call before opening any connection.
     */
    fun requireNetworkAllowed(urlString: String) {
        if (!isLocalOnly()) return
        val host = try {
            java.net.URL(urlString).host.lowercase()
        } catch (e: Exception) {
            throw LocalOnlyBlockedException(urlString)
        }
        if (!localHosts.contains(host)) {
            throw LocalOnlyBlockedException(host)
        }
    }
}
