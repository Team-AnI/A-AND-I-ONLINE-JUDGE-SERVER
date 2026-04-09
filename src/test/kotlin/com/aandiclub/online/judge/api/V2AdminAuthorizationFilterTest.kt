package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.v2.V2AdminAuthorizationFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class V2AdminAuthorizationFilterTest {
    private val filter = V2AdminAuthorizationFilter(ObjectMapper())

    @Test
    fun `admin role can access v2 admin endpoints`() {
        val chain = RecordingChain()
        val exchange = exchange()
        exchange.attributes[JwtExchangeAttributes.ROLES] = setOf("ADMIN")

        filter.filter(exchange, chain).block()

        assertEquals(1, chain.called)
        assertEquals(null, exchange.response.statusCode)
    }

    @Test
    fun `user role gets 403 on v2 admin endpoints`() {
        val chain = RecordingChain()
        val exchange = exchange()
        exchange.attributes[JwtExchangeAttributes.ROLES] = setOf("USER")

        filter.filter(exchange, chain).block()

        assertEquals(0, chain.called)
        assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
    }

    private fun exchange(): ServerWebExchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/v2/admin/submissions").build())

    private class RecordingChain : WebFilterChain {
        var called: Int = 0

        override fun filter(exchange: ServerWebExchange): Mono<Void> {
            called += 1
            return Mono.empty()
        }
    }
}
