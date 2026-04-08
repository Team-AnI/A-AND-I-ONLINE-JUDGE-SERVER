package com.aandiclub.online.judge.worker

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.config.ProblemCatalogProperties
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.repository.ProblemRepository
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxInput
import com.aandiclub.online.judge.sandbox.SandboxRunner
import com.aandiclub.online.judge.service.SubmissionEventPublisher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.math.BigDecimal

@Component
class JudgeWorker(
    private val sandboxRunner: SandboxRunner,
    private val submissionRepository: SubmissionRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val sandboxProperties: SandboxProperties,
    private val problemRepository: ProblemRepository,
    private val problemCatalogProperties: ProblemCatalogProperties,
    private val submissionEventPublisher: SubmissionEventPublisher?,
) {
    private val log = LoggerFactory.getLogger(JudgeWorker::class.java)

    suspend fun execute(
        submission: Submission,
        testCases: List<TestCase>? = null,
    ): Unit = withContext(SubmissionMdc.context(submission.id)) {
        val resolvedTestCases = testCases ?: loadTestCases(submission.problemId)
        log.info("Judge worker started: cases={}", resolvedTestCases.size)

        submission.status = SubmissionStatus.RUNNING
        submissionRepository.save(submission).awaitSingle()

        val channel = "submission:${submission.id}"
        if (resolvedTestCases.isEmpty()) {
            val result = TestCaseResult(
                caseId = 0,
                status = TestCaseStatus.RUNTIME_ERROR,
                error = "No test cases configured for problemId=${submission.problemId}",
            )
            submission.testCaseResults = listOf(result)
            submission.status = SubmissionStatus.RUNTIME_ERROR
            submission.completedAt = Instant.now()
            submissionRepository.save(submission).awaitSingle()

            val resultPayload = objectMapper.writeValueAsString(result)
            redisTemplate.convertAndSend(channel, resultPayload).awaitSingle()
            val donePayload = objectMapper.writeValueAsString(
                mapOf(
                    "event" to "done",
                    "submissionId" to submission.id,
                    "overallStatus" to SubmissionStatus.RUNTIME_ERROR.name,
                )
            )
            redisTemplate.convertAndSend(channel, donePayload).awaitSingle()
            submissionEventPublisher?.publishJudgeCompleted(
                publicCode = submission.submitterPublicCode,
                problemId = submission.problemId,
                testCases = resolvedTestCases,
                results = submission.testCaseResults,
            )
            return@withContext
        }

        val results = resolvedTestCases.map { testCase ->
            val output = sandboxRunner.run(
                language = submission.language,
                input = SandboxInput(code = submission.code, args = testCase.args),
            )
            val status = resolveStatus(
                runnerStatus = output.status,
                output = output.output,
                memoryMb = output.memoryMb,
                expectedOutput = testCase.expectedOutput,
            )
            TestCaseResult(
                caseId = testCase.caseId,
                status = status,
                timeMs = output.timeMs,
                memoryMb = output.memoryMb,
                output = output.output,
                error = output.error,
            )
        }

        results.forEach { result ->
            val payload = objectMapper.writeValueAsString(result)
            redisTemplate.convertAndSend(channel, payload).awaitSingle()
        }

        val finalStatus = results.firstOrNull { it.status != TestCaseStatus.PASSED }
            ?.status
            ?.toSubmissionStatus()
            ?: SubmissionStatus.ACCEPTED

        submission.testCaseResults = results
        submission.status = finalStatus
        submission.completedAt = Instant.now()
        submissionRepository.save(submission).awaitSingle()

        val donePayload = objectMapper.writeValueAsString(
            mapOf(
                "event" to "done",
                "submissionId" to submission.id,
                "overallStatus" to finalStatus.name,
            )
        )
        redisTemplate.convertAndSend(channel, donePayload).awaitSingle()
        submissionEventPublisher?.publishJudgeCompleted(
            publicCode = submission.submitterPublicCode,
            problemId = submission.problemId,
            testCases = resolvedTestCases,
            results = results,
        )
        Unit
    }

    private fun resolveStatus(
        runnerStatus: TestCaseStatus,
        output: Any?,
        memoryMb: Double,
        expectedOutput: Any?,
    ): TestCaseStatus {
        if (runnerStatus != TestCaseStatus.PASSED) return runnerStatus
        if (memoryMb > sandboxProperties.memoryLimitMb) return TestCaseStatus.MEMORY_LIMIT_EXCEEDED
        return if (outputsMatch(output, expectedOutput)) TestCaseStatus.PASSED else TestCaseStatus.WRONG_ANSWER
    }

    private fun outputsMatch(actual: Any?, expected: Any?): Boolean {
        if (actual == null || expected == null) return actual == expected

        val normalizedExpected = if (expected is String) {
            try {
                when (actual) {
                    is List<*> -> objectMapper.readValue(expected, List::class.java)
                    is Map<*, *> -> objectMapper.readValue(expected, Map::class.java)
                    is Number -> try { BigDecimal(expected) } catch (_: Exception) { expected }
                    else -> expected
                }
            } catch (_: Exception) {
                expected
            }
        } else {
            expected
        }

        return when {
            actual is Number && normalizedExpected is Number ->
                actual.toBigDecimal().compareTo(normalizedExpected.toBigDecimal()) == 0
            actual is List<*> && normalizedExpected is List<*> ->
                actual.size == normalizedExpected.size && actual.zip(normalizedExpected).all { (a, e) -> outputsMatch(a, e) }
            actual is Map<*, *> && normalizedExpected is Map<*, *> -> {
                val actualKeys = actual.keys.map { it.toString() }.toSet()
                val expectedKeys = normalizedExpected.keys.map { it.toString() }.toSet()
                actualKeys == expectedKeys && actual.entries.all { (key, value) ->
                    val expectedValue = normalizedExpected.entries.firstOrNull { it.key.toString() == key.toString() }?.value
                    outputsMatch(value, expectedValue)
                }
            }
            else -> actual == normalizedExpected
        }
    }

    private fun Number.toBigDecimal(): BigDecimal = BigDecimal(this.toString())

    private fun TestCaseStatus.toSubmissionStatus(): SubmissionStatus = when (this) {
        TestCaseStatus.PASSED -> SubmissionStatus.ACCEPTED
        TestCaseStatus.WRONG_ANSWER -> SubmissionStatus.WRONG_ANSWER
        TestCaseStatus.TIME_LIMIT_EXCEEDED -> SubmissionStatus.TIME_LIMIT_EXCEEDED
        TestCaseStatus.MEMORY_LIMIT_EXCEEDED -> SubmissionStatus.MEMORY_LIMIT_EXCEEDED
        TestCaseStatus.RUNTIME_ERROR -> SubmissionStatus.RUNTIME_ERROR
        TestCaseStatus.COMPILE_ERROR -> SubmissionStatus.COMPILE_ERROR
    }

    private suspend fun loadTestCases(problemId: String): List<TestCase> {
        val dbProblem = problemRepository.findById(problemId).awaitSingleOrNull()
        if (dbProblem != null) return dbProblem.testCases
        return problemCatalogProperties.find(problemId)?.testCases ?: emptyList()
    }
}
