package com.aandiclub.online.judge.logging.api

import org.springframework.web.server.ServerWebExchange
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

class ApiLogContext(
    val traceId: String = UUID.randomUUID().toString(),
    val requestId: String = UUID.randomUUID().toString(),
    val startedAt: Instant = Instant.now(),
    private val startedAtNanos: Long = System.nanoTime(),
) {
    private val requestBuffer = ByteArrayOutputStream()
    private val responseBuffer = ByteArrayOutputStream()

    var handledException: Throwable? = null
        private set

    fun appendRequest(bytes: ByteArray) {
        requestBuffer.write(bytes)
    }

    fun appendResponse(bytes: ByteArray) {
        responseBuffer.write(bytes)
    }

    fun requestBody(): ByteArray = requestBuffer.toByteArray()

    fun responseBody(): ByteArray = responseBuffer.toByteArray()

    fun latencyMs(): Long = ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0)

    fun recordException(throwable: Throwable) {
        handledException = throwable
    }

    companion object {
        const val ATTRIBUTE_NAME: String = "api.log.context"

        fun get(exchange: ServerWebExchange): ApiLogContext? =
            exchange.getAttribute(ATTRIBUTE_NAME)
    }
}
