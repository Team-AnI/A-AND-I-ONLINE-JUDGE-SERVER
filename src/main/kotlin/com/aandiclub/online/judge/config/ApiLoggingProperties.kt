package com.aandiclub.online.judge.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "judge.api-logging")
data class ApiLoggingProperties(
    val env: String = "local",
    val serviceName: String = "judge",
    val version: String = "0.0.1-SNAPSHOT",
    val domainCode: Int = 5,
    val instanceId: String = "unknown-instance",
)
