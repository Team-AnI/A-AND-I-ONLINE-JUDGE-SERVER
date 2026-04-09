package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.api.v2.support.V2ExchangeAttributes
import com.aandiclub.online.judge.api.v2.support.V2ResponseWriter
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
class V2HeaderFilter(
    private val objectMapper: ObjectMapper,
) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!isTarget(exchange)) return chain.filter(exchange)

        val deviceOS = exchange.request.headers.getFirst(HEADER_DEVICE_OS)?.trim().orEmpty()
        if (deviceOS.isBlank()) {
            return V2ResponseWriter.writeError(
                exchange = exchange,
                objectMapper = objectMapper,
                status = HttpStatus.BAD_REQUEST,
                errorCode = V2ErrorCode.COMMON_MISSING_REQUIRED_HEADER,
                message = "Missing required header: $HEADER_DEVICE_OS",
                value = HEADER_DEVICE_OS,
                alert = "필수 헤더가 누락되었습니다.",
            )
        }

        val timestamp = exchange.request.headers.getFirst(HEADER_TIMESTAMP)?.trim().orEmpty()
        if (timestamp.isBlank()) {
            return V2ResponseWriter.writeError(
                exchange = exchange,
                objectMapper = objectMapper,
                status = HttpStatus.BAD_REQUEST,
                errorCode = V2ErrorCode.COMMON_MISSING_REQUIRED_HEADER,
                message = "Missing required header: $HEADER_TIMESTAMP",
                value = HEADER_TIMESTAMP,
                alert = "필수 헤더가 누락되었습니다.",
            )
        }

        exchange.attributes[V2ExchangeAttributes.DEVICE_OS] = deviceOS
        exchange.attributes[V2ExchangeAttributes.TIMESTAMP] = timestamp
        exchange.request.headers.getFirst(HEADER_SALT)?.trim()?.takeIf { it.isNotBlank() }?.let {
            exchange.attributes[V2ExchangeAttributes.SALT] = it
        }
        return chain.filter(exchange)
    }

    private fun isTarget(exchange: ServerWebExchange): Boolean {
        if (exchange.request.method?.name() == "OPTIONS") return false
        return exchange.request.path.pathWithinApplication().value().startsWith("/v2/")
    }

    companion object {
        const val HEADER_DEVICE_OS = "deviceOS"
        const val HEADER_TIMESTAMP = "timestamp"
        const val HEADER_SALT = "salt"
    }
}
