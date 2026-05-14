package com.aandiclub.online.judge.logging.api

import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.config.ApiLoggingProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class RequestResponseLoggingFilterTest {
    private val objectMapper = ObjectMapper()
    private val maskingUtil = MaskingUtil()
    private val apiLogFactory = ApiLogFactory(
        objectMapper = objectMapper,
        maskingUtil = maskingUtil,
        properties = ApiLoggingProperties(
            env = "test",
            serviceName = "judge",
            version = "1.2.3",
            domainCode = 5,
            instanceId = "judge-test-1",
        ),
    )
    private val writer = RecordingApiLogWriter()
    private val filter = RequestResponseLoggingFilter(apiLogFactory, writer)

    @Test
    fun `writes info api log for successful response with masked request`() {
        val exchange = exchange(
            path = "/v2/auth/login?mode=normal",
            body =
                """
                {
                  "loginId": "hans1234",
                  "password": "secret",
                  "accessToken": "aaa",
                  "refreshToken": "bbb"
                }
                """.trimIndent(),
            headers = mapOf(
                "deviceOS" to "ANDROID",
                "Authenticate" to "Bearer jwt-token",
                "X-Trace-Id" to "gateway-trace-1",
                "timestamp" to "1712600000",
                "salt" to "salt-value",
            ),
        )

        filter.filter(exchange, SuccessChain(objectMapper)).block()

        val entry = writer.entries.single()
        assertEquals("INFO", entry.level)
        assertEquals("API", entry.logType)
        assertEquals("HTTP request completed", entry.message)
        assertEquals("judge", entry.service.domain)
        assertEquals("gateway-trace-1", entry.trace.traceId)
        assertTrue(entry.trace.requestId.isNotBlank())
        assertEquals("Bearer ****", entry.headers.Authenticate)
        assertEquals("****", entry.headers.salt)
        assertEquals("ANDROID", entry.headers.deviceOS)
        assertEquals("han******", (entry.request.body as Map<*, *>)["loginId"])
        assertEquals("****", (entry.request.body as Map<*, *>)["password"])
        assertEquals("****", (entry.request.body as Map<*, *>)["accessToken"])
        assertEquals("****", (entry.response.data as Map<*, *>)["accessToken"])
        assertEquals(true, entry.response.success)
        assertEquals("judge", entry.tags[0])
        assertEquals("auth", entry.tags[1])
        assertEquals("success", entry.tags[2])
        assertEquals("create", entry.tags[3])
    }

    @Test
    fun `writes warn api error log for failure response`() {
        writer.entries.clear()
        val exchange = exchange(
            path = "/v2/auth/login",
            body = """{"loginId":"hans1234","password":"wrong"}""",
            headers = mapOf("Authenticate" to "Bearer jwt-token"),
        )

        filter.filter(exchange, FailureChain(objectMapper)).block()

        val entry = writer.entries.single()
        assertEquals("WARN", entry.level)
        assertEquals("SECURITY", entry.logType)
        assertTrue(entry.message.contains("invalid credentials"))
        assertFalse(entry.response.success)
        assertEquals(V2ErrorCode.JUDGE_AUTH_INVALID_TOKEN.code, entry.response.error?.code)
        assertEquals("fail", entry.tags[2])
    }

    @Test
    fun `writes api slow log when latency threshold is exceeded`() {
        writer.entries.clear()
        val slowFactory = ApiLogFactory(
            objectMapper = objectMapper,
            maskingUtil = maskingUtil,
            properties = ApiLoggingProperties(
                env = "test",
                serviceName = "judge",
                domain = "judge",
                version = "1.2.3",
                domainCode = 5,
                instanceId = "judge-test-1",
                slowThresholdMs = 0,
            ),
        )
        val slowFilter = RequestResponseLoggingFilter(slowFactory, writer)
        val exchange = exchange(
            path = "/v2/submissions",
            body = """{"code":"print(1)"}""",
            headers = mapOf("Authenticate" to "Bearer jwt-token"),
        )

        slowFilter.filter(exchange, SuccessChain(objectMapper)).block()

        val entry = writer.entries.single()
        assertEquals("WARN", entry.level)
        assertEquals("API_SLOW", entry.logType)
    }

    @Test
    fun `writes error detail without stack trace body for handled exception`() {
        writer.entries.clear()
        val exchange = exchange(
            path = "/v2/submissions",
            body = """{"code":"print(1)"}""",
            headers = mapOf("Authenticate" to "Bearer jwt-token"),
        )

        filter.filter(exchange, HandledExceptionChain(objectMapper)).block()

        val entry = writer.entries.single()
        assertEquals("API_ERROR", entry.logType)
        assertEquals(V2ErrorCode.JUDGE_INTERNAL_SERVER_ERROR.code, entry.response.error?.code)
        assertEquals("java.lang.IllegalStateException", entry.errorDetail?.exceptionClass)
        assertEquals("handled boom", entry.errorDetail?.exceptionMessage)
        assertTrue(entry.errorDetail?.stackTraceHash?.startsWith("sha256:") == true)
        assertEquals(true, entry.errorDetail?.handled)
    }

    @Test
    fun `creates structured judge event log`() {
        val entry = apiLogFactory.createEvent(
            eventType = "JUDGE_COMPLETED",
            logType = "EVENT",
            traceId = "trace-event-1",
            resourceId = "sub-1",
            metadata = mapOf("accessToken" to "raw-token"),
        )

        assertEquals("EVENT", entry.logType)
        assertEquals("trace-event-1", entry.trace.traceId)
        assertEquals("JUDGE_COMPLETED", entry.event?.eventType)
        assertEquals("sub-1", entry.event?.resourceId)
        assertEquals("****", entry.event?.metadata?.get("accessToken"))
    }

    private fun exchange(path: String, body: String, headers: Map<String, String>): MockServerWebExchange {
        val builder = MockServerHttpRequest.post(path)
            .contentType(MediaType.APPLICATION_JSON)
        headers.forEach(builder::header)
        return MockServerWebExchange.from(builder.body(body))
    }

    private class RecordingApiLogWriter : ApiLogWriter {
        val entries: MutableList<ApiLogEntry> = mutableListOf()

        override fun write(entry: ApiLogEntry) {
            entries += entry
        }
    }

    private class SuccessChain(
        private val objectMapper: ObjectMapper,
    ) : WebFilterChain {
        override fun filter(exchange: ServerWebExchange): Mono<Void> =
            DataBufferUtils.join(exchange.request.body)
                .flatMap { dataBuffer ->
                    DataBufferUtils.release(dataBuffer)
                    val body = objectMapper.writeValueAsBytes(
                        V2ApiResponses.success(
                            mapOf(
                                "loginId" to "hans1234",
                                "accessToken" to "server-access-token",
                            )
                        )
                    )
                    exchange.response.statusCode = HttpStatus.OK
                    exchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body)))
                }
    }

    private class FailureChain(
        private val objectMapper: ObjectMapper,
    ) : WebFilterChain {
        override fun filter(exchange: ServerWebExchange): Mono<Void> =
            DataBufferUtils.join(exchange.request.body)
                .flatMap { dataBuffer ->
                    DataBufferUtils.release(dataBuffer)
                    val body = objectMapper.writeValueAsBytes(
                        V2ApiResponses.error(
                            errorCode = V2ErrorCode.JUDGE_AUTH_INVALID_TOKEN,
                            message = "Login failed: invalid credentials",
                            value = "loginId",
                            alert = "로그인 정보를 확인해주세요.",
                        )
                    )
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body)))
                }
    }

    private class HandledExceptionChain(
        private val objectMapper: ObjectMapper,
    ) : WebFilterChain {
        override fun filter(exchange: ServerWebExchange): Mono<Void> =
            DataBufferUtils.join(exchange.request.body)
                .flatMap { dataBuffer ->
                    DataBufferUtils.release(dataBuffer)
                    ApiLogContext.get(exchange)?.recordException(IllegalStateException("handled boom"), handled = true)
                    val body = objectMapper.writeValueAsBytes(
                        V2ApiResponses.error(
                            errorCode = V2ErrorCode.JUDGE_INTERNAL_SERVER_ERROR,
                            message = "handled boom",
                        )
                    )
                    exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
                    exchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body)))
                }
    }
}
