package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.api.v2.support.V2ResponseWriter
import com.aandiclub.online.judge.config.RateLimitProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

@Component
class V2SubmissionRateLimitFilter(
    private val properties: RateLimitProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : WebFilter {

    private data class WindowCounter(
        val windowStartEpochSecond: Long,
        val count: Int,
    )

    private val counters = ConcurrentHashMap<String, WindowCounter>()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!properties.enabled || !isTarget(exchange)) return chain.filter(exchange)

        val now = clock.instant().epochSecond
        val key = "${resolveClientIp(exchange)}:/v2/submissions"
        val state = counters.compute(key) { _, current ->
            if (current == null || now - current.windowStartEpochSecond >= properties.windowSeconds) {
                WindowCounter(now, 1)
            } else {
                current.copy(count = current.count + 1)
            }
        } ?: WindowCounter(now, 1)

        if (counters.size > 10_000) {
            pruneExpired(now)
        }

        if (state.count <= properties.submitRequests) return chain.filter(exchange)

        exchange.response.headers.set("Retry-After", properties.windowSeconds.toString())
        return V2ResponseWriter.writeError(
            exchange = exchange,
            objectMapper = objectMapper,
            status = HttpStatus.TOO_MANY_REQUESTS,
            errorCode = V2ErrorCode.JUDGE_RATE_LIMIT_EXCEEDED,
            message = "Rate limit exceeded",
            value = "submitRequests",
            alert = "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
        )
    }

    private fun isTarget(exchange: ServerWebExchange): Boolean =
        exchange.request.method?.name() == "POST" &&
            exchange.request.path.pathWithinApplication().value() == "/v2/submissions"

    private fun resolveClientIp(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            return forwarded.substringBefore(",").trim()
        }
        return exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
    }

    private fun pruneExpired(nowEpochSecond: Long) {
        val threshold = nowEpochSecond - (properties.windowSeconds * 2)
        counters.entries.removeIf { (_, value) -> value.windowStartEpochSecond < threshold }
    }
}
