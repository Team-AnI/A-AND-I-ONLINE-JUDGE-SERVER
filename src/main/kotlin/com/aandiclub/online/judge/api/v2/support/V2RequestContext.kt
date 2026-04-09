package com.aandiclub.online.judge.api.v2.support

import org.springframework.web.server.ServerWebExchange

data class V2RequestContext(
    val deviceOS: String,
    val timestamp: String,
    val salt: String?,
)

object V2ExchangeAttributes {
    const val DEVICE_OS = "v2.deviceOS"
    const val TIMESTAMP = "v2.timestamp"
    const val SALT = "v2.salt"
}

fun ServerWebExchange.v2RequestContext(): V2RequestContext =
    V2RequestContext(
        deviceOS = getAttribute<String>(V2ExchangeAttributes.DEVICE_OS).orEmpty(),
        timestamp = getAttribute<String>(V2ExchangeAttributes.TIMESTAMP).orEmpty(),
        salt = getAttribute<String>(V2ExchangeAttributes.SALT),
    )
