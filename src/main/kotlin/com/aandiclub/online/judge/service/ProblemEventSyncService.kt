package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.domain.Problem
import com.aandiclub.online.judge.domain.ProblemMetadata
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.logging.api.JudgeEventLogger
import com.aandiclub.online.judge.logging.api.JudgeEventType
import com.aandiclub.online.judge.repository.ProblemRepository
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.text.Regex

enum class ProblemEventSyncOutcome {
    UPSERTED,
    SKIPPED,
}

@Service
class ProblemEventSyncService(
    private val problemRepository: ProblemRepository,
    private val objectMapper: ObjectMapper,
    private val testCaseEventPublisher: TestCaseEventPublisher?,
    private val judgeEventLogger: JudgeEventLogger? = null,
) {
    private val log = LoggerFactory.getLogger(ProblemEventSyncService::class.java)

    suspend fun sync(rawBody: String): ProblemEventSyncOutcome {
        val root = runCatching { objectMapper.readTree(rawBody) }
            .getOrElse { ex ->
                log.warn("Skipping problem event: invalid JSON body ({})", ex.message)
                return ProblemEventSyncOutcome.SKIPPED
            }

        val payload = unwrapSnsEnvelope(root) ?: return ProblemEventSyncOutcome.SKIPPED

        if (!shouldProcessEvent(payload)) {
            log.debug("Skipping problem event: eventType not in allowed list")
            return ProblemEventSyncOutcome.SKIPPED
        }

        val problemId = extractProblemId(payload)
        if (problemId.isNullOrBlank()) {
            log.debug("Skipping problem event: no problem UUID field")
            return ProblemEventSyncOutcome.SKIPPED
        }

        val testCasesNode = findFirst(payload, "testCases", "test_cases", "cases")
        if (testCasesNode == null || !testCasesNode.isArray) {
            log.debug("Skipping problem event: no testCases array, problemId={}", problemId)
            return ProblemEventSyncOutcome.SKIPPED
        }

        val testCases = testCasesNode.mapIndexedNotNull { idx, testCaseNode ->
            val outputNode = findFirst(testCaseNode, "expectedOutput", "expected_output", "output", "expected")
            val expectedOutput = nodeToValue(outputNode) ?: return@mapIndexedNotNull null

            val caseId = findFirst(testCaseNode, "caseId", "case_id")?.asInt(idx + 1) ?: (idx + 1)
            val argsNode = findFirst(testCaseNode, "args", "input", "inputs")
            val args = nodeToArgs(argsNode)
            val score = findFirst(testCaseNode, "score")?.asInt(0) ?: 0

            TestCase(
                caseId = caseId,
                args = args,
                expectedOutput = expectedOutput,
                score = score,
            )
        }

        val metadata = extractMetadata(payload)

        try {
            problemRepository.save(
                Problem(
                    problemId = problemId,
                    testCases = testCases,
                    metadata = metadata,
                )
            ).awaitSingle()
        } catch (ex: Exception) {
            judgeEventLogger?.eventError(
                eventType = JudgeEventType.TESTCASE_UPDATED,
                errorCode = V2ErrorCode.TESTCASE_UPDATE_FAILED,
                throwable = ex,
                resourceId = problemId,
                metadata = mapOf(
                    "problemId" to problemId,
                    "testCaseCount" to testCases.size,
                ),
            )
            throw ex
        }

        log.info("Problem test cases upserted: problemId={}, cases={}", problemId, testCases.size)
        judgeEventLogger?.event(
            eventType = JudgeEventType.TESTCASE_UPDATED,
            resourceId = problemId,
            metadata = mapOf(
                "problemId" to problemId,
                "testCaseCount" to testCases.size,
            ),
        )

        testCaseEventPublisher?.publishTestCaseUpdated(problemId, testCases.size)

        return ProblemEventSyncOutcome.UPSERTED
    }

    private fun shouldProcessEvent(payload: JsonNode): Boolean {
        val eventType = findFirst(payload, "eventType", "event_type", "type")
            ?.asText()
            ?: return true // eventType이 없으면 기본 처리 (하위 호환성)

        return eventType in listOf(
            "PROBLEM_CREATED",
            "PROBLEM_UPDATED",
            "TEST_CASE_UPDATED"
        )
    }

    private fun unwrapSnsEnvelope(root: JsonNode): JsonNode? {
        val messageNode = root.get("Message") ?: return root
        return when {
            messageNode.isTextual -> runCatching { objectMapper.readTree(messageNode.asText()) }.getOrNull()
            messageNode.isObject -> messageNode
            else -> null
        }
    }

    private fun extractMetadata(payload: JsonNode): ProblemMetadata? {
        val metaNode = findFirst(payload, "metadata") ?: return null
        if (!metaNode.isObject) return null

        val courseId = findFirst(metaNode, "courseId", "course_id")?.asText()?.takeIf { it.isNotBlank() }
        val startAt = findFirst(metaNode, "startAt", "start_at")?.asText()
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val endAt = findFirst(metaNode, "endAt", "end_at")?.asText()
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

        if (courseId == null && startAt == null && endAt == null) return null
        return ProblemMetadata(courseId = courseId, startAt = startAt, endAt = endAt)
    }

    private fun extractProblemId(node: JsonNode): String? =
        findFirst(node, "problemId", "problem_id", "problemUuid", "problem_uuid", "uuid", "id")
            ?.asText()
            ?.takeIf { it.isNotBlank() }

    private fun findFirst(node: JsonNode, vararg keys: String): JsonNode? =
        keys.asSequence()
            .mapNotNull { key -> node.get(key) }
            .firstOrNull { !it.isNull }

    private fun nodeToArgs(node: JsonNode?): List<Any?> {
        if (node == null || node.isNull) return emptyList()
        return if (node.isArray) {
            node.map { nodeToValue(it) }
        } else {
            listOf(nodeToValue(node))
        }
    }

    private fun nodeToValue(node: JsonNode?): Any? {
        if (node == null || node.isNull) return null
        return when {
            node.isObject -> buildMap {
                val fields = node.properties()
                fields.forEach { (key, value) -> put(key, nodeToValue(value)) }
            }
            node.isArray -> node.map { nodeToValue(it) }
            node.isTextual -> inferTextValue(node.asText())
            node.isBoolean -> node.booleanValue()
            node.isIntegralNumber -> node.longValue().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            node.isFloatingPointNumber -> node.doubleValue()
            else -> objectMapper.convertValue(node, Any::class.java)
        }
    }

    private fun inferTextValue(raw: String): Any? {
        val value = raw.trim()
        if (value.isEmpty()) return raw

        if (value.equals("null", ignoreCase = true)) return null
        if (value.equals("true", ignoreCase = true)) return true
        if (value.equals("false", ignoreCase = true)) return false
        if (INTEGER_PATTERN.matches(value)) {
            return value.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: raw
        }
        if (DECIMAL_PATTERN.matches(value)) {
            return value.toDoubleOrNull() ?: raw
        }
        if ((value.startsWith("[") && value.endsWith("]")) || (value.startsWith("{") && value.endsWith("}"))) {
            val parsed = runCatching { objectMapper.readTree(value) }.getOrNull()
            if (parsed != null) return nodeToValue(parsed)
        }

        return raw
    }

    companion object {
        private val INTEGER_PATTERN = Regex("^-?(0|[1-9][0-9]*)$")
        private val DECIMAL_PATTERN = Regex("^-?(?:0|[1-9][0-9]*)\\.[0-9]+(?:[eE][+-]?[0-9]+)?$|^-?(?:0|[1-9][0-9]*)(?:[eE][+-]?[0-9]+)$")
    }
}
