package com.aandiclub.online.judge.logging.api

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class JsonApiLogWriter(
    private val objectMapper: ObjectMapper,
) : ApiLogWriter {
    private val log = LoggerFactory.getLogger(API_JSON_LOGGER)

    override fun write(entry: ApiLogEntry) {
        val payload = objectMapper.writeValueAsString(entry)
        when (entry.level) {
            "ERROR" -> log.error(payload)
            "WARN" -> log.warn(payload)
            else -> log.info(payload)
        }
    }

    companion object {
        const val API_JSON_LOGGER: String = "API_JSON_LOGGER"
    }
}
