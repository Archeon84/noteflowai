package com.noteflowai.app.data

import kotlinx.coroutines.delay
import java.util.Collections

/**
 * Simple sliding-window rate limiter for API requests.
 * @param maxRequestsPerMin Maximum requests allowed per 60s window. 0 = unlimited.
 */
class RateLimiter(val maxRequestsPerMin: Int) {
    private val timestamps = Collections.synchronizedList(mutableListOf<Long>())

    /**
     * Suspends until a request slot is available (or returns immediately if unlimited).
     */
    suspend fun acquire() {
        if (maxRequestsPerMin <= 0) return
        val windowMs = 60_000L
        while (true) {
            val now = System.currentTimeMillis()
            synchronized(timestamps) {
                timestamps.removeAll { now - it > windowMs }
                if (timestamps.size < maxRequestsPerMin) {
                    timestamps.add(now)
                    return
                }
            }
            val oldest = synchronized(timestamps) { timestamps.first() }
            val waitMs = (oldest + windowMs) - now
            if (waitMs > 0) delay(waitMs.coerceAtMost(windowMs))
        }
    }
}
