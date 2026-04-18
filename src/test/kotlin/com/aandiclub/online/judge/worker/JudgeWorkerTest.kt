package com.aandiclub.online.judge.worker

import com.aandiclub.online.judge.config.ProblemCatalogProperties
import com.aandiclub.online.judge.config.ProblemItem
import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Problem
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.repository.ProblemRepository
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxCaseInput
import com.aandiclub.online.judge.sandbox.SandboxCaseOutput
import com.aandiclub.online.judge.sandbox.SandboxRunner
import com.aandiclub.online.judge.service.JudgePerformanceMonitorService
import com.aandiclub.online.judge.service.SubmissionEventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class JudgeWorkerTest {
    private val sandboxRunner = mockk<SandboxRunner>()
    private val submissionRepository = mockk<SubmissionRepository>()
    private val problemRepository = mockk<ProblemRepository>()
    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val objectMapper = ObjectMapper()
    private val sandboxProperties = SandboxProperties(memoryLimitMb = 128)
    private val submissionEventPublisher = mockk<SubmissionEventPublisher>(relaxed = true)
    private val judgePerformanceMonitorService = mockk<JudgePerformanceMonitorService>(relaxed = true)
    private val problemCatalogProperties = ProblemCatalogProperties(
        items = mapOf(
            "quiz-101" to ProblemItem(
                testCases = quiz101Cases,
            ),
        ),
    )

    private val judgeWorker = JudgeWorker(
        sandboxRunner = sandboxRunner,
        submissionRepository = submissionRepository,
        redisTemplate = redisTemplate,
        objectMapper = objectMapper,
        sandboxProperties = sandboxProperties,
        problemRepository = problemRepository,
        problemCatalogProperties = problemCatalogProperties,
        judgePerformanceMonitorService = judgePerformanceMonitorService,
        submissionEventPublisher = submissionEventPublisher,
    )

    init {
        every { problemRepository.findById(any<String>()) } returns Mono.empty<Problem>()
    }

    @Test
    fun `execute publishes case result and done when accepted`() = runTest {
        val submission = Submission(
            id = "sub-1",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        val testCases = listOf(TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = 8))
        coEvery {
            sandboxRunner.runCase(
                Language.PYTHON,
                submission.code,
                SandboxCaseInput(caseId = 1, args = listOf(3, 5)),
            )
        } returns
            SandboxCaseOutput(
                caseId = 1,
                status = TestCaseStatus.PASSED,
                output = 8,
                error = null,
                timeMs = 1.2,
                memoryMb = 2.1,
            )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, testCases)

        coVerify(exactly = 1) {
            sandboxRunner.runCase(
                Language.PYTHON,
                submission.code,
                SandboxCaseInput(caseId = 1, args = listOf(3, 5)),
            )
        }
        verify(atLeast = 2) { submissionRepository.save(any()) }
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-1",
                match { it.contains("\"caseId\":1") && it.contains("\"status\":\"PASSED\"") },
            )
        }
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-1",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"ACCEPTED\"") },
            )
        }
        coVerify(exactly = 1) {
            submissionEventPublisher.publishJudgeCompleted(
                submissionId = "sub-1",
                publicCode = "A00123",
                problemId = "quiz-101",
                testCases = testCases,
                results = match { it.size == 1 && it.first().status == TestCaseStatus.PASSED },
            )
        }
    }

    @Test
    fun `execute marks wrong answer when output mismatches expected`() = runTest {
        val submission = Submission(
            id = "sub-2",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        val testCases = listOf(TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = 8))
        coEvery { sandboxRunner.runCase(Language.PYTHON, submission.code, any()) } returns
            SandboxCaseOutput(
                caseId = 1,
                status = TestCaseStatus.PASSED,
                output = 7,
                error = null,
                timeMs = 1.0,
                memoryMb = 2.0,
            )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, testCases)

        assertEquals(SubmissionStatus.WRONG_ANSWER, submission.status)
        assertEquals(TestCaseStatus.WRONG_ANSWER, submission.testCaseResults.first().status)
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-2",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"WRONG_ANSWER\"") },
            )
        }
    }

    @Test
    fun `execute marks runtime error when no test cases are provided`() = runTest {
        val submission = Submission(
            id = "sub-3",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "unknown-problem",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, emptyList())

        assertEquals(SubmissionStatus.RUNTIME_ERROR, submission.status)
        assertEquals(1, submission.testCaseResults.size)
        assertEquals(TestCaseStatus.RUNTIME_ERROR, submission.testCaseResults.first().status)
        coVerify(exactly = 0) { sandboxRunner.runCase(any(), any(), any()) }
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-3",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"RUNTIME_ERROR\"") },
            )
        }
    }

    @Test
    fun `execute marks memory limit exceeded when memory is over configured limit`() = runTest {
        val submission = Submission(
            id = "sub-4",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        val testCases = listOf(TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = 8))
        coEvery { sandboxRunner.runCase(Language.PYTHON, submission.code, any()) } returns
            SandboxCaseOutput(
                caseId = 1,
                status = TestCaseStatus.PASSED,
                output = 8,
                error = null,
                timeMs = 5.0,
                memoryMb = 256.0,
            )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, testCases)

        assertEquals(SubmissionStatus.MEMORY_LIMIT_EXCEEDED, submission.status)
        assertEquals(TestCaseStatus.MEMORY_LIMIT_EXCEEDED, submission.testCaseResults.first().status)
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-4",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"MEMORY_LIMIT_EXCEEDED\"") },
            )
        }
    }

    @Test
    fun `execute loads test cases from problem catalog when cases are not provided`() = runTest {
        val submission = Submission(
            id = "sub-5",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        coEvery { sandboxRunner.runCase(Language.PYTHON, submission.code, any()) } answers {
            val input = thirdArg<SandboxCaseInput>()
            SandboxCaseOutput(
                caseId = input.caseId,
                status = TestCaseStatus.PASSED,
                output = 0,
                error = null,
                timeMs = 1.0,
                memoryMb = 2.0,
            )
        }
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission)

        coVerify(exactly = 10) {
            sandboxRunner.runCase(
                Language.PYTHON,
                submission.code,
                any(),
            )
        }
        assertEquals(SubmissionStatus.WRONG_ANSWER, submission.status)
        assertEquals(10, submission.testCaseResults.size)
    }

    @Test
    fun `execute preserves final test case order while publishing as cases complete`() = runTest {
        val submission = Submission(
            id = "sub-6",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        val testCases = listOf(
            TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = 8),
            TestCase(caseId = 2, args = listOf(10, 2), expectedOutput = 12),
        )
        coEvery { sandboxRunner.runCase(Language.PYTHON, submission.code, any()) } coAnswers {
            val input = thirdArg<SandboxCaseInput>()
            if (input.caseId == 1) delay(100)
            SandboxCaseOutput(
                caseId = input.caseId,
                status = TestCaseStatus.PASSED,
                output = input.args.filterIsInstance<Number>().sumOf { it.toInt() },
                error = null,
                timeMs = 1.0,
                memoryMb = 2.0,
            )
        }
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, testCases)

        assertEquals(listOf(1, 2), submission.testCaseResults.map { it.caseId })
        assertEquals(listOf(8, 12), submission.testCaseResults.map { it.output })
        verify(exactly = 2) {
            redisTemplate.convertAndSend(
                "submission:sub-6",
                match { it.contains("\"caseId\":") && it.contains("\"status\":\"PASSED\"") },
            )
        }
    }

    companion object {
        private val quiz101Cases = listOf(
            TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = 8),
            TestCase(caseId = 2, args = listOf(10, 2), expectedOutput = 12),
            TestCase(caseId = 3, args = listOf(0, 0), expectedOutput = 0),
            TestCase(caseId = 4, args = listOf(-7, 4), expectedOutput = -3),
            TestCase(caseId = 5, args = listOf(100, 250), expectedOutput = 350),
            TestCase(caseId = 6, args = listOf(1, -1), expectedOutput = 0),
            TestCase(caseId = 7, args = listOf(999, 1), expectedOutput = 1000),
            TestCase(caseId = 8, args = listOf(42, 58), expectedOutput = 100),
            TestCase(caseId = 9, args = listOf(-20, -22), expectedOutput = -42),
            TestCase(caseId = 10, args = listOf(1234, 4321), expectedOutput = 5555),
        )
    }
}
