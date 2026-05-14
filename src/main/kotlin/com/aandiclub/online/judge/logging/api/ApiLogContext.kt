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
    var exceptionHandled: Boolean = false
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

    fun recordException(throwable: Throwable, handled: Boolean = false) {
        handledException = throwable
        exceptionHandled = handled
    }

    companion object {
        const val ATTRIBUTE_NAME: String = "api.log.context"
        const val HEADER_TRACE_ID: String = "X-Trace-Id"
        const val HEADER_REQUEST_ID: String = "X-Request-Id"
        private const val HEADER_B3_TRACE_ID: String = "X-B3-TraceId"

        fun get(exchange: ServerWebExchange): ApiLogContext? =
            exchange.getAttribute(ATTRIBUTE_NAME)

        fun from(exchange: ServerWebExchange): ApiLogContext =
            ApiLogContext(
                traceId = incomingTraceId(exchange) ?: UUID.randomUUID().toString(),
                requestId = UUID.randomUUID().toString(),
            )

        private fun incomingTraceId(exchange: ServerWebExchange): String? =
            listOfNotNull(
                exchange.request.headers.getFirst(HEADER_TRACE_ID),
                exchange.request.headers.getFirst(HEADER_B3_TRACE_ID),
            ).firstOrNull { it.isNotBlank() }
    }
}
