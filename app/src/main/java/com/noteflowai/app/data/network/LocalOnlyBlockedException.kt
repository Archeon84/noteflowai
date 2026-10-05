package com.noteflowai.app.data.network

import java.io.IOException

/**
 * Thrown when an outbound network call is blocked by Local-Only Mode isolation.
 */
class LocalOnlyBlockedException(
    val host: String,
    message: String = "Outbound network call to '$host' blocked: Local-Only mode is enabled."
) : IOException(message)
