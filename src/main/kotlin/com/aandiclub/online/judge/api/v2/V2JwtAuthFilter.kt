package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.JwtExchangeAttributes
import com.aandiclub.online.judge.api.JwtTokenValidator
import com.aandiclub.online.judge.config.JwtAuthProperties
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.api.v2.support.V2ResponseWriter
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
class V2JwtAuthFilter(
    private val properties: JwtAuthProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : WebFilter {
    private val log = LoggerFactory.getLogger(V2JwtAuthFilter::class.java)
    private val validator = JwtTokenValidator(properties, objectMapper, clock)

    init {
        require(!properties.enabled || properties.signingKey.isNotBlank()) {
            "judge.jwt-auth.signing-key must be configured when JWT auth is enabled"
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!properties.enabled || !isTarget(exchange)) return chain.filter(exchange)

        val auth = exchange.request.headers.getFirst(HEADER_AUTHENTICATE)
        if (auth.isNullOrBlank() || !auth.startsWith("Bearer ")) {
            return V2ResponseWriter.writeError(
                exchange = exchange,
                objectMapper = objectMapper,
                status = HttpStatus.UNAUTHORIZED,
                errorCode = V2ErrorCode.JUDGE_AUTH_MISSING_HEADER,
                message = "Missing or invalid Authenticate header",
                value = HEADER_AUTHENTICATE,
                alert = "인증 정보가 올바르지 않습니다.",
            )
        }

        val token = auth.removePrefix("Bearer ").trim()
        val result = validator.validate(token)
        if (!result.valid) {
            log.debug("JWT validation failed for v2: {}", result.reason)
            return V2ResponseWriter.writeError(
                exchange = exchange,
                objectMapper = objectMapper,
                status = HttpStatus.UNAUTHORIZED,
                errorCode = V2ErrorCode.JUDGE_AUTH_INVALID_TOKEN,
                message = result.reason ?: "Invalid JWT",
                value = HEADER_AUTHENTICATE,
                alert = "인증 토큰이 유효하지 않습니다.",
            )
        }

        exchange.attributes[JwtExchangeAttributes.SUBJECT] = result.subject
        exchange.attributes[JwtExchangeAttributes.ROLES] = result.roles
        return chain.filter(exchange)
    }

    private fun isTarget(exchange: ServerWebExchange): Boolean {
        if (exchange.request.method?.name() == "OPTIONS") return false
        return exchange.request.path.pathWithinApplication().value().startsWith("/v2/")
    }

    companion object {
        const val HEADER_AUTHENTICATE = "Authenticate"
    }
}
