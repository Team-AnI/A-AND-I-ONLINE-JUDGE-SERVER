package com.aandiclub.online.judge.logging.api

import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JudgeEventLogger(
    private val apiLogFactory: ApiLogFactory,
    private val apiLogWriter: ApiLogWriter,
) {
    private val log = LoggerFactory.getLogger(JudgeEventLogger::class.java)

    fun event(
        eventType: JudgeEventType,
        traceId: String? = null,
        resourceId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        write(
            apiLogFactory.createEvent(
                eventType = eventType.name,
                logType = "EVENT",
                traceId = traceId,
                resourceId = resourceId,
                metadata = metadata,
            )
        )
    }

    fun eventError(
        eventType: JudgeEventType,
        errorCode: V2ErrorCode,
        throwable: Throwable,
        traceId: String? = null,
        resourceId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        write(
            apiLogFactory.createEvent(
                eventType = eventType.name,
                logType = "EVENT_ERROR",
                traceId = traceId,
                resourceId = resourceId,
                metadata = metadata,
                errorCode = errorCode,
                throwable = throwable,
            )
        )
    }

    private fun write(entry: ApiLogEntry) {
        runCatching {
            apiLogWriter.write(entry)
        }.onFailure { throwable ->
            log.error("Failed to emit structured judge event log", throwable)
        }
    }
}

enum class JudgeEventType {
    JUDGE_REQUESTED,
    JUDGE_STARTED,
    JUDGE_COMPLETED,
    JUDGE_RESULT_SAVED,
    TESTCASE_CREATED,
    TESTCASE_UPDATED,
    TESTCASE_DELETED,
}
