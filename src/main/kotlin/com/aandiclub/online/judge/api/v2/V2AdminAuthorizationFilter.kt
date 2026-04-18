package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.JwtExchangeAttributes
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
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
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
class V2AdminAuthorizationFilter(
    private val objectMapper: ObjectMapper,
) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!isTarget(exchange)) return chain.filter(exchange)

        val roles = exchange.getAttribute<Set<String>>(JwtExchangeAttributes.ROLES).orEmpty()
        if (roles.any { it in ADMIN_ROLES }) return chain.filter(exchange)

        return V2ResponseWriter.writeError(
            exchange = exchange,
            objectMapper = objectMapper,
            status = HttpStatus.FORBIDDEN,
            errorCode = V2ErrorCode.JUDGE_FORBIDDEN,
            message = "ADMIN role is required",
            value = "role",
            alert = "관리자 권한이 필요합니다.",
        )
    }

    private fun isTarget(exchange: ServerWebExchange): Boolean {
        if (exchange.request.method?.name() == "OPTIONS") return false
        val path = exchange.request.path.pathWithinApplication().value()
        return path.startsWith("/v2/admin/") || path == "/v2/monitor" || path.startsWith("/v2/monitor/")
    }

    companion object {
        private val ADMIN_ROLES = setOf("ADMIN", "SUPER_ADMIN", "ROOT")
    }
}
