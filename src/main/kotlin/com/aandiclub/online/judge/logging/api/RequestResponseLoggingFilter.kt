package com.aandiclub.online.judge.logging.api

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestResponseLoggingFilter(
    private val apiLogFactory: ApiLogFactory,
    private val apiLogWriter: ApiLogWriter,
) : WebFilter {
    private val log = LoggerFactory.getLogger(RequestResponseLoggingFilter::class.java)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!isApiPath(exchange.request.path.pathWithinApplication().value())) {
            return chain.filter(exchange)
        }

        val context = ApiLogContext()
        exchange.attributes[ApiLogContext.ATTRIBUTE_NAME] = context

        val bufferFactory = exchange.response.bufferFactory()

        val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody(): Flux<DataBuffer> =
                super.getBody().map { dataBuffer ->
                    val bytes = ByteArray(dataBuffer.readableByteCount())
                    dataBuffer.read(bytes)
                    context.appendRequest(bytes)
                    DataBufferUtils.release(dataBuffer)
                    bufferFactory.wrap(bytes)
                }
        }

        val decoratedResponse = object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: org.reactivestreams.Publisher<out DataBuffer>): Mono<Void> =
                super.writeWith(Flux.from(body).map(::capture))

            override fun writeAndFlushWith(body: org.reactivestreams.Publisher<out org.reactivestreams.Publisher<out DataBuffer>>): Mono<Void> =
                super.writeAndFlushWith(
                    Flux.from(body).map { publisher ->
                        Flux.from(publisher).map(::capture)
                    }
                )

            private fun capture(dataBuffer: DataBuffer): DataBuffer {
                val bytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bytes)
                context.appendResponse(bytes)
                DataBufferUtils.release(dataBuffer)
                return bufferFactory.wrap(bytes)
            }
        }

        val mutatedExchange = exchange.mutate()
            .request(decoratedRequest)
            .response(decoratedResponse)
            .build()

        return chain.filter(mutatedExchange)
            .doOnError(context::recordException)
            .doFinally {
                runCatching {
                    apiLogWriter.write(apiLogFactory.create(mutatedExchange, context))
                }.onFailure { throwable ->
                    log.error("Failed to emit structured API log", throwable)
                }
            }
    }

    private fun isApiPath(path: String): Boolean =
        path.startsWith("/v1/") || path.startsWith("/v2/")
}
