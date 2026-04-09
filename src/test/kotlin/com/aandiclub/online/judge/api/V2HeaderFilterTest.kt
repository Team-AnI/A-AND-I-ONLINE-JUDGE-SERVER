package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.v2.V2HeaderFilter
import com.aandiclub.online.judge.api.v2.support.V2ExchangeAttributes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class V2HeaderFilterTest {
    private val filter = V2HeaderFilter(ObjectMapper())

    @Test
    fun `missing deviceOS returns 400 envelope`() {
        val chain = RecordingChain()
        val exchange = exchange(timestamp = "1712600000")

        filter.filter(exchange, chain).block()

        assertEquals(0, chain.called)
        assertEquals(HttpStatus.BAD_REQUEST, exchange.response.statusCode)
    }

    @Test
    fun `valid headers are stored in exchange attributes`() {
        val chain = RecordingChain()
        val exchange = exchange(deviceOS = "ANDROID", timestamp = "1712600000", salt = "optional")

        filter.filter(exchange, chain).block()

        assertEquals(1, chain.called)
        assertEquals("ANDROID", exchange.getAttribute<String>(V2ExchangeAttributes.DEVICE_OS))
        assertEquals("1712600000", exchange.getAttribute<String>(V2ExchangeAttributes.TIMESTAMP))
        assertEquals("optional", exchange.getAttribute<String>(V2ExchangeAttributes.SALT))
    }

    private fun exchange(deviceOS: String? = null, timestamp: String? = null, salt: String? = null): ServerWebExchange {
        val builder = MockServerHttpRequest.get("/v2/submissions/sub-1")
        deviceOS?.let { builder.header("deviceOS", it) }
        timestamp?.let { builder.header("timestamp", it) }
        salt?.let { builder.header("salt", it) }
        return MockServerWebExchange.from(builder.build())
    }

    private class RecordingChain : WebFilterChain {
        var called: Int = 0

        override fun filter(exchange: ServerWebExchange): Mono<Void> {
            called += 1
            return Mono.empty()
        }
    }
}
