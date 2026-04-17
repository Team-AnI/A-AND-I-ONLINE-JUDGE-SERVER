package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.api.dto.MyProblemSubmissionRecord
import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.worker.JudgeWorker
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.ReactiveSubscription
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import org.springframework.data.redis.core.ReactiveValueOperations

class SubmissionServiceTest {

    private val submissionRepository = mockk<SubmissionRepository>()
    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val listenerContainer = mockk<ReactiveRedisMessageListenerContainer>()
    private val judgeWorker = mockk<JudgeWorker>(relaxed = true)
    private val judgeWorkerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val judgeWorkerSemaphore = Semaphore(2)
    private val objectMapper = ObjectMapper()
    private val submissionProperties = com.aandiclub.online.judge.config.SubmissionProperties(dedupTtlMinutes = 5)

    private val userRepository = mockk<com.aandiclub.online.judge.repository.UserRepository>(relaxed = true)

    private val service = SubmissionService(
        submissionRepository,
        userRepository,
        redisTemplate,
        listenerContainer,
        judgeWorker,
        judgeWorkerScope,
        judgeWorkerSemaphore,
        objectMapper,
        submissionProperties,
    )

    @AfterEach
    fun tearDown() {
        judgeWorkerScope.cancel()
    }

    @Test
    fun `createSubmission saves submitter identity and returns SubmissionAccepted`() = runTest {
        val mockUser = com.aandiclub.online.judge.domain.User(
            userId = "user-1",
            publicCode = "A00123",
            username = "testuser"
        )
        every { userRepository.findByPublicCode("A00123") } returns Mono.just(mockUser)

        val request = SubmissionRequest(
            publicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
        )
        val savedSubmission = Submission(
            id = "saved-uuid",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
        )

        val savedSlot = slot<Submission>()
        val valueOps = mockk<org.springframework.data.redis.core.ReactiveValueOperations<String, String>>(relaxed = true)

        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.empty()
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } returns Mono.just(true)
        every { submissionRepository.save(capture(savedSlot)) } returns Mono.just(savedSubmission)

        val result = service.createSubmission(request, "user-1")

        assertEquals("saved-uuid", result.submissionId)
        assertEquals("/v1/submissions/saved-uuid/stream", result.streamUrl)
        verify(exactly = 1) { submissionRepository.save(any()) }
        coVerify(timeout = 1_000, exactly = 1) { judgeWorker.execute(match { it.id == "saved-uuid" }) }
        assertEquals("user-1", savedSlot.captured.submitterId)
        assertEquals("A00123", savedSlot.captured.submitterPublicCode)
        assertEquals(SubmissionStatus.PENDING, savedSlot.captured.status)
    }

    @Test
    fun `getResult returns SubmissionResult for owner`() = runTest {
        val submission = Submission(
            id = "existing-uuid",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-303",
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            status = SubmissionStatus.ACCEPTED,
        )
        every { submissionRepository.findById("existing-uuid") } returns Mono.just(submission)

        val result = service.getResult("existing-uuid", "user-1", isAdmin = false)

        assertNotNull(result)
        assertEquals("existing-uuid", result!!.submissionId)
        assertEquals(SubmissionStatus.ACCEPTED, result.status)
    }

    @Test
    fun `getResult masks output for all problems`() = runTest {
        val submission = Submission(
            id = "existing-masked",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "problem-any",
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            status = SubmissionStatus.ACCEPTED,
            testCaseResults = listOf(
                TestCaseResult(
                    caseId = 1,
                    status = TestCaseStatus.PASSED,
                    output = 8,
                )
            ),
        )
        every { submissionRepository.findById("existing-masked") } returns Mono.just(submission)

        val result = service.getResult("existing-masked", "user-1", isAdmin = false)

        assertEquals("비공개", result!!.testCases.first().output)
        assertEquals(TestCaseStatus.PASSED, result.testCases.first().status)
    }

    @Test
    fun `getResult returns 404 when submission does not exist`() = runTest {
        every { submissionRepository.findById("missing-id") } returns Mono.empty()

        val error = assertThrows(ResponseStatusException::class.java) {
            kotlinx.coroutines.runBlocking { service.getResult("missing-id", "user-1", isAdmin = false) }
        }

        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
    }

    @Test
    fun `getResult returns 403 when requester is not owner`() = runTest {
        val submission = Submission(
            id = "sub-1",
            submitterId = "owner-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-303",
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            status = SubmissionStatus.ACCEPTED,
        )
        every { submissionRepository.findById("sub-1") } returns Mono.just(submission)

        val error = assertThrows(ResponseStatusException::class.java) {
            kotlinx.coroutines.runBlocking { service.getResult("sub-1", "other-user", isAdmin = false) }
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
    }

    @Test
    fun `getResult allows admin to access any submission`() = runTest {
        val submission = Submission(
            id = "sub-2",
            submitterId = "owner-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-303",
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            status = SubmissionStatus.ACCEPTED,
        )
        every { submissionRepository.findById("sub-2") } returns Mono.just(submission)

        val result = service.getResult("sub-2", "admin-user", isAdmin = true)

        assertEquals("sub-2", result!!.submissionId)
    }

    @Test
    fun `getResult returns null when submission is pending or running`() = runTest {
        val pending = Submission(
            id = "sub-pending",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-303",
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            status = SubmissionStatus.PENDING,
        )
        val running = pending.copy(id = "sub-running", status = SubmissionStatus.RUNNING)

        every { submissionRepository.findById("sub-pending") } returns Mono.just(pending)
        every { submissionRepository.findById("sub-running") } returns Mono.just(running)

        val pendingResult = service.getResult("sub-pending", "user-1", isAdmin = false)
        val runningResult = service.getResult("sub-running", "user-1", isAdmin = false)

        assertNull(pendingResult)
        assertNull(runningResult)
    }

    @Test
    fun `getProblemSubmissions returns submitter history in repository order`() = runTest {
        val newer = Submission(
            id = "sub-new",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-404",
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int): Int = a + b",
            status = SubmissionStatus.ACCEPTED,
            createdAt = Instant.parse("2026-03-15T10:00:00Z"),
            completedAt = Instant.parse("2026-03-15T10:00:01Z"),
        )
        val older = Submission(
            id = "sub-old",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-404",
            language = Language.PYTHON,
            code = "print(1)",
            status = SubmissionStatus.WRONG_ANSWER,
            createdAt = Instant.parse("2026-03-15T09:00:00Z"),
            completedAt = Instant.parse("2026-03-15T09:00:05Z"),
        )
        every {
            submissionRepository.findAllBySubmitterIdAndProblemIdOrderByCreatedAtDesc("user-1", "quiz-404")
        } returns Flux.just(newer, older)

        val result = service.getProblemSubmissions("quiz-404", "user-1")

        assertEquals(
            listOf(
                MyProblemSubmissionRecord(
                    submissionId = "sub-new",
                    problemId = "quiz-404",
                    language = Language.KOTLIN,
                    status = SubmissionStatus.ACCEPTED,
                    testCases = emptyList(),
                    createdAt = OffsetDateTime.parse("2026-03-15T19:00:00+09:00"),
                    completedAt = OffsetDateTime.parse("2026-03-15T19:00:01+09:00"),
                ),
                MyProblemSubmissionRecord(
                    submissionId = "sub-old",
                    problemId = "quiz-404",
                    language = Language.PYTHON,
                    status = SubmissionStatus.WRONG_ANSWER,
                    testCases = emptyList(),
                    createdAt = OffsetDateTime.parse("2026-03-15T18:00:00+09:00"),
                    completedAt = OffsetDateTime.parse("2026-03-15T18:00:05+09:00"),
                ),
            ),
            result
        )
    }

    @Test
    fun `getProblemSubmissions masks output for all problems`() = runTest {
        every {
            submissionRepository.findAllBySubmitterIdAndProblemIdOrderByCreatedAtDesc("user-1", "problem-any")
        } returns Flux.just(
            Submission(
                id = "sub-1",
                submitterId = "user-1",
                submitterPublicCode = "A00123",
                problemId = "problem-any",
                language = Language.PYTHON,
                code = "print(1)",
                status = SubmissionStatus.ACCEPTED,
                testCaseResults = listOf(
                    TestCaseResult(
                        caseId = 1,
                        status = TestCaseStatus.PASSED,
                        output = "real-output",
                    )
                ),
            )
        )

        val result = service.getProblemSubmissions("problem-any", "user-1")

        assertEquals(1, result.size)
        assertEquals("비공개", result.first().testCases.first().output)
        assertEquals(TestCaseStatus.PASSED, result.first().testCases.first().status)
    }

    @Test
    fun `getAllSubmissions returns admin records with submitter metadata`() = runTest {
        val newer = Submission(
            id = "sub-new",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-404",
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int): Int = a + b",
            status = SubmissionStatus.ACCEPTED,
            testCaseResults = listOf(
                TestCaseResult(
                    caseId = 1,
                    status = TestCaseStatus.PASSED,
                    output = 3,
                )
            ),
            createdAt = Instant.parse("2026-03-15T10:00:00Z"),
            completedAt = Instant.parse("2026-03-15T10:00:01Z"),
        )
        every { submissionRepository.findAllByOrderByCreatedAtDesc() } returns Flux.just(newer)

        val result = service.getAllSubmissions()

        assertEquals(
            listOf(
                AdminSubmissionRecord(
                    submissionId = "sub-new",
                    submitterId = "user-1",
                    submitterPublicCode = "A00123",
                    problemId = "quiz-404",
                    language = Language.KOTLIN,
                    code = "fun solution(a: Int, b: Int): Int = a + b",
                    status = SubmissionStatus.ACCEPTED,
                    testCases = listOf(
                        TestCaseResult(
                            caseId = 1,
                            status = TestCaseStatus.PASSED,
                            output = "비공개",
                        )
                    ),
                    createdAt = OffsetDateTime.parse("2026-03-15T19:00:00+09:00"),
                    completedAt = OffsetDateTime.parse("2026-03-15T19:00:01+09:00"),
                ),
            ),
            result
        )
    }

    @Test
    fun `streamResults maps redis messages to sse events for owner`() = runTest {
        val submissionId = "sub-123"
        val submission = Submission(
            id = submissionId,
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "print(1)",
        )
        val payload = """{"caseId":1,"status":"PASSED"}"""
        val redisMessage = mockk<ReactiveSubscription.Message<String, String>>()
        every { submissionRepository.findById(submissionId) } returns Mono.just(submission)
        every { redisMessage.message } returns payload
        every { listenerContainer.receive(ChannelTopic.of("submission:$submissionId")) } returns Flux.just(redisMessage)

        val events = service.streamResults(submissionId, "user-1", isAdmin = false).toList()

        assertEquals(1, events.size)
        assertEquals("test_case_result", events[0].event())
        val masked = objectMapper.readTree(events[0].data())
        assertEquals("1", masked.path("caseId").asText())
        assertEquals("PASSED", masked.path("status").asText())
        assertEquals("비공개", masked.path("output").asText())
    }

    @Test
    fun `streamResults masks output for all problems`() = runTest {
        val submissionId = "sub-any"
        val submission = Submission(
            id = submissionId,
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "problem-any",
            language = Language.PYTHON,
            code = "print(1)",
        )
        val payload = """{"caseId":3,"status":"TIME_LIMIT_EXCEEDED","timeMs":2001.0,"memoryMb":24.7,"output":"x","error":"timeout"}"""
        val redisMessage = mockk<ReactiveSubscription.Message<String, String>>()
        every { submissionRepository.findById(submissionId) } returns Mono.just(submission)
        every { redisMessage.message } returns payload
        every { listenerContainer.receive(ChannelTopic.of("submission:$submissionId")) } returns Flux.just(redisMessage)

        val events = service.streamResults(submissionId, "user-1", isAdmin = false).toList()

        assertEquals(1, events.size)
        assertEquals("test_case_result", events[0].event())
        val masked = objectMapper.readTree(events[0].data())
        assertEquals("3", masked.path("caseId").asText())
        assertEquals("TIME_LIMIT_EXCEEDED", masked.path("status").asText())
        assertEquals("2001.0", masked.path("timeMs").asText())
        assertEquals("24.7", masked.path("memoryMb").asText())
        assertEquals("비공개", masked.path("output").asText())
        assertEquals("timeout", masked.path("error").asText())
    }

    @Test
    fun `createSubmission returns cached submission for duplicate within 5 minutes`() = runTest {
        val mockUser = com.aandiclub.online.judge.domain.User(
            userId = "user-1",
            publicCode = "A00123",
            username = "testuser"
        )
        every { userRepository.findByPublicCode("A00123") } returns Mono.just(mockUser)

        val request = SubmissionRequest(
            publicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
        )
        val cachedSubmissionId = "cached-uuid"
        val valueOps = mockk<org.springframework.data.redis.core.ReactiveValueOperations<String, String>>()

        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.just(cachedSubmissionId)

        val result = service.createSubmission(request, "user-1")

        assertEquals(cachedSubmissionId, result.submissionId)
        assertEquals("/v1/submissions/$cachedSubmissionId/stream", result.streamUrl)
        verify(exactly = 0) { submissionRepository.save(any()) }
        coVerify(exactly = 0) { judgeWorker.execute(any()) }
    }

    @Test
    fun `createSubmission creates new submission and caches when no duplicate exists`() = runTest {
        val mockUser = com.aandiclub.online.judge.domain.User(
            userId = "user-1",
            publicCode = "A00123",
            username = "testuser"
        )
        every { userRepository.findByPublicCode("A00123") } returns Mono.just(mockUser)

        val request = SubmissionRequest(
            publicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
        )
        val savedSubmission = Submission(
            id = "new-uuid",
            submitterId = "user-1",
            submitterPublicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
        )
        val valueOps = mockk<org.springframework.data.redis.core.ReactiveValueOperations<String, String>>(relaxed = true)

        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.empty()
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } returns Mono.just(true)
        every { submissionRepository.save(any()) } returns Mono.just(savedSubmission)

        val result = service.createSubmission(request, "user-1")

        assertEquals("new-uuid", result.submissionId)
        assertEquals("/v1/submissions/new-uuid/stream", result.streamUrl)
        verify(exactly = 1) { submissionRepository.save(any()) }
        verify(exactly = 1) { valueOps.set(any(), eq("new-uuid"), any<java.time.Duration>()) }
        coVerify(timeout = 1_000, exactly = 1) { judgeWorker.execute(match { it.id == "new-uuid" }) }
    }
}
