package com.aandiclub.online.judge.logging.api

import org.springframework.web.server.ServerWebExchange
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

class ApiLogContext(
    val traceId: String = UUID.randomUUID().toString(),
    val requestId: String = UUID.randomUUID().toString(),
    val startedAt: Instant = Instant.now(),
    private val maxCaptureBytes: Int = DEFAULT_MAX_CAPTURE_BYTES,
    private val startedAtNanos: Long = System.nanoTime(),
) {
    private val requestBuffer = ByteArrayOutputStream()
    private val responseBuffer = ByteArrayOutputStream()

    var handledException: Throwable? = null
        private set
    var exceptionHandled: Boolean = false
        private set
    var requestBodyTruncated: Boolean = false
        private set
    var responseBodyTruncated: Boolean = false
        private set
    var responseBodySkippedReason: String? = null
        private set

    fun appendRequest(bytes: ByteArray) {
        requestBodyTruncated = appendBounded(requestBuffer, bytes, requestBodyTruncated)
    }

    fun appendResponse(bytes: ByteArray) {
        if (responseBodySkippedReason != null) return
        responseBodyTruncated = appendBounded(responseBuffer, bytes, responseBodyTruncated)
    }

    fun requestBody(): ByteArray = requestBuffer.toByteArray()

    fun responseBody(): ByteArray = responseBuffer.toByteArray()

    fun latencyMs(): Long = ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0)

    fun recordException(throwable: Throwable, handled: Boolean = false) {
        handledException = throwable
        exceptionHandled = handled
    }

    fun skipResponseBody(reason: String) {
        responseBodySkippedReason = reason
        responseBuffer.reset()
        responseBodyTruncated = false
    }

    private fun appendBounded(
        buffer: ByteArrayOutputStream,
        bytes: ByteArray,
        alreadyTruncated: Boolean,
    ): Boolean {
        if (alreadyTruncated || maxCaptureBytes <= 0) return true
        val remaining = maxCaptureBytes - buffer.size()
        if (remaining <= 0) return true
        if (bytes.size <= remaining) {
            buffer.write(bytes)
            return false
        }
        buffer.write(bytes, 0, remaining)
        return true
    }

    companion object {
        const val ATTRIBUTE_NAME: String = "api.log.context"
        const val HEADER_TRACE_ID: String = "X-Trace-Id"
        const val HEADER_REQUEST_ID: String = "X-Request-Id"
        const val STREAMING_RESPONSE_SKIPPED: String = "streaming response skipped"
        const val DEFAULT_MAX_CAPTURE_BYTES: Int = 64 * 1024
        private const val HEADER_B3_TRACE_ID: String = "X-B3-TraceId"

        fun get(exchange: ServerWebExchange): ApiLogContext? =
            exchange.getAttribute(ATTRIBUTE_NAME)

        fun from(
            exchange: ServerWebExchange,
            maxCaptureBytes: Int = DEFAULT_MAX_CAPTURE_BYTES,
        ): ApiLogContext =
            ApiLogContext(
                traceId = incomingTraceId(exchange) ?: UUID.randomUUID().toString(),
                requestId = UUID.randomUUID().toString(),
                maxCaptureBytes = maxCaptureBytes.coerceAtLeast(0),
            )

        private fun incomingTraceId(exchange: ServerWebExchange): String? =
            listOfNotNull(
                exchange.request.headers.getFirst(HEADER_TRACE_ID),
                exchange.request.headers.getFirst(HEADER_B3_TRACE_ID),
            ).firstOrNull { it.isNotBlank() }
    }
}
