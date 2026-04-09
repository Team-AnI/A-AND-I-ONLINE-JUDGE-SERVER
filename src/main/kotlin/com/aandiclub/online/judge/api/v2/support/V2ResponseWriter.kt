package com.aandiclub.online.judge.api.v2.support

import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

object V2ResponseWriter {
    fun writeError(
        exchange: ServerWebExchange,
        objectMapper: ObjectMapper,
        status: HttpStatusCode,
        errorCode: V2ErrorCode,
        message: String,
        value: String = "",
        alert: String = errorCode.defaultAlert,
    ): Mono<Void> {
        val response = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val body = objectMapper.writeValueAsBytes(
            V2ApiResponses.error(
                errorCode = errorCode,
                message = message,
                value = value,
                alert = alert,
            )
        )
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
    }
}
