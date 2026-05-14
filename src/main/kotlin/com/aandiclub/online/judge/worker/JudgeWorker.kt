package com.aandiclub.online.judge.worker

import com.aandiclub.online.judge.config.ProblemCatalogProperties
import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.logging.api.JudgeEventLogger
import com.aandiclub.online.judge.logging.api.JudgeEventType
import com.aandiclub.online.judge.repository.ProblemRepository
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxCaseInput
import com.aandiclub.online.judge.sandbox.SandboxCaseOutput
import com.aandiclub.online.judge.sandbox.SandboxRunner
import com.aandiclub.online.judge.service.JudgePerformanceMonitorService
import com.aandiclub.online.judge.service.SubmissionEventPublisher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant

@Component
class JudgeWorker(
    private val sandboxRunner: SandboxRunner,
    private val submissionRepository: SubmissionRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val sandboxProperties: SandboxProperties,
    private val problemRepository: ProblemRepository,
    private val problemCatalogProperties: ProblemCatalogProperties,
    private val judgePerformanceMonitorService: JudgePerformanceMonitorService,
    private val submissionEventPublisher: SubmissionEventPublisher?,
    private val judgeEventLogger: JudgeEventLogger? = null,
) {
    private val log = LoggerFactory.getLogger(JudgeWorker::class.java)

    suspend fun execute(
        submission: Submission,
        testCases: List<TestCase>? = null,
        traceId: String? = null,
    ): Unit = withContext(SubmissionMdc.context(submission.id)) {
        val resolvedTestCases = testCases ?: loadTestCases(submission.problemId)
        log.info("Judge worker started: cases={}", resolvedTestCases.size)
        judgeEventLogger?.event(
            eventType = JudgeEventType.JUDGE_STARTED,
            traceId = traceId,
            resourceId = submission.id,
            metadata = mapOf(
                "submissionId" to submission.id,
                "problemId" to submission.problemId,
                "caseCount" to resolvedTestCases.size,
            ),
        )
        judgePerformanceMonitorService.onSubmissionStarted(submission, resolvedTestCases.size)

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
            judgeEventLogger?.event(
                eventType = JudgeEventType.JUDGE_RESULT_SAVED,
                traceId = traceId,
                resourceId = submission.id,
                metadata = mapOf(
                    "submissionId" to submission.id,
                    "problemId" to submission.problemId,
                    "status" to submission.status.name,
                ),
            )
            judgePerformanceMonitorService.onSubmissionCompleted(submission, SubmissionStatus.RUNTIME_ERROR)

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
            if (submissionEventPublisher != null) {
                submissionEventPublisher.publishJudgeCompleted(
                    submissionId = submission.id,
                    publicCode = submission.submitterPublicCode,
                    problemId = submission.problemId,
                    testCases = resolvedTestCases,
                    results = submission.testCaseResults,
                )
            } else {
                log.warn("SubmissionEventPublisher is not configured, skipping judge completed event: submissionId={}", submission.id)
            }
            judgeEventLogger?.event(
                eventType = JudgeEventType.JUDGE_COMPLETED,
                traceId = traceId,
                resourceId = submission.id,
                metadata = mapOf(
                    "submissionId" to submission.id,
                    "problemId" to submission.problemId,
                    "status" to submission.status.name,
                ),
            )
            return@withContext
        }

        val caseSemaphore = Semaphore(sandboxProperties.caseConcurrency.coerceAtLeast(1))
        val resultsByCaseId = coroutineScope {
            resolvedTestCases.map { testCase ->
                async {
                    caseSemaphore.withPermit {
                        judgePerformanceMonitorService.onCaseStarted(submission.id)
                        val output = runCaseSafely(submission, testCase, traceId)
                        val result = toTestCaseResult(testCase, output)
                        judgePerformanceMonitorService.onCaseFinished(submission.id, result)
                        val payload = objectMapper.writeValueAsString(result)
                        redisTemplate.convertAndSend(channel, payload).awaitSingle()
                        testCase.caseId to result
                    }
                }
            }.awaitAll().toMap()
        }

        val results = resolvedTestCases.map { testCase ->
            resultsByCaseId[testCase.caseId]
                ?: TestCaseResult(
                    caseId = testCase.caseId,
                    status = TestCaseStatus.RUNTIME_ERROR,
                    error = "RUNTIME_ERROR: missing sandbox result for caseId=${testCase.caseId}",
                )
        }

        val finalStatus = results.firstOrNull { it.status != TestCaseStatus.PASSED }
            ?.status
            ?.toSubmissionStatus()
            ?: SubmissionStatus.ACCEPTED

        submission.testCaseResults = results
        submission.status = finalStatus
        submission.completedAt = Instant.now()
        submissionRepository.save(submission).awaitSingle()
        judgeEventLogger?.event(
            eventType = JudgeEventType.JUDGE_RESULT_SAVED,
            traceId = traceId,
            resourceId = submission.id,
            metadata = mapOf(
                "submissionId" to submission.id,
                "problemId" to submission.problemId,
                "status" to finalStatus.name,
            ),
        )
        judgePerformanceMonitorService.onSubmissionCompleted(submission, finalStatus)

        val donePayload = objectMapper.writeValueAsString(
            mapOf(
                "event" to "done",
                "submissionId" to submission.id,
                "overallStatus" to finalStatus.name,
            )
        )
        redisTemplate.convertAndSend(channel, donePayload).awaitSingle()
        if (submissionEventPublisher != null) {
            submissionEventPublisher.publishJudgeCompleted(
                submissionId = submission.id,
                publicCode = submission.submitterPublicCode,
                problemId = submission.problemId,
                testCases = resolvedTestCases,
                results = results,
            )
        } else {
            log.warn("SubmissionEventPublisher is not configured, skipping judge completed event: submissionId={}", submission.id)
        }
        judgeEventLogger?.event(
            eventType = JudgeEventType.JUDGE_COMPLETED,
            traceId = traceId,
            resourceId = submission.id,
            metadata = mapOf(
                "submissionId" to submission.id,
                "problemId" to submission.problemId,
                "status" to finalStatus.name,
            ),
        )
        Unit
    }

    private fun toTestCaseResult(testCase: TestCase, output: SandboxCaseOutput): TestCaseResult {
        val status = resolveStatus(
            runnerStatus = output.status,
            output = output.output,
            memoryMb = output.memoryMb,
            expectedOutput = testCase.expectedOutput,
        )
        return TestCaseResult(
            caseId = testCase.caseId,
            status = status,
            timeMs = output.timeMs,
            memoryMb = output.memoryMb,
            output = output.output,
            error = output.error,
        )
    }

    private suspend fun runCaseSafely(
        submission: Submission,
        testCase: TestCase,
        traceId: String?,
    ): SandboxCaseOutput = try {
        sandboxRunner.runCase(
            language = submission.language,
            code = submission.code,
            testCase = SandboxCaseInput(caseId = testCase.caseId, args = testCase.args),
        )
    } catch (ex: Exception) {
        log.error("Sandbox case execution failed: submissionId={}, caseId={}", submission.id, testCase.caseId, ex)
        judgeEventLogger?.eventError(
            eventType = JudgeEventType.JUDGE_COMPLETED,
            errorCode = if (ex.message?.contains("timeout", ignoreCase = true) == true) {
                V2ErrorCode.SANDBOX_EXECUTION_TIMEOUT
            } else {
                V2ErrorCode.SANDBOX_EXECUTION_FAILED
            },
            throwable = ex,
            traceId = traceId,
            resourceId = submission.id,
            metadata = mapOf(
                "submissionId" to submission.id,
                "problemId" to submission.problemId,
                "caseId" to testCase.caseId,
            ),
        )
        SandboxCaseOutput(
            caseId = testCase.caseId,
            status = TestCaseStatus.RUNTIME_ERROR,
            output = null,
            error = "RUNTIME_ERROR: ${ex.message ?: "sandbox execution failed"}",
            timeMs = 0.0,
            memoryMb = 0.0,
        )
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
