package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxInput
import com.aandiclub.online.judge.sandbox.SandboxOutput
import com.aandiclub.online.judge.sandbox.SandboxRunner
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import tools.jackson.databind.ObjectMapper

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "judge.rate-limit.enabled=false",
        "judge.jwt-auth.enabled=false",
    ],
)
class SubmissionWorkflowE2ETest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var submissionRepository: SubmissionRepository

    @Autowired
    private lateinit var userRepository: com.aandiclub.online.judge.repository.UserRepository

    @MockkBean
    private lateinit var sandboxRunner: SandboxRunner

    @MockkBean
    private lateinit var redisTemplate: ReactiveStringRedisTemplate

    @MockkBean
    private lateinit var listenerContainer: ReactiveRedisMessageListenerContainer

    private lateinit var webTestClient: WebTestClient
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setup() {
        submissionRepository.deleteAll().block()
        userRepository.deleteAll().block()
        userRepository.save(
            com.aandiclub.online.judge.domain.User(
                userId = "anonymous",
                publicCode = "A00123",
                username = "anonymous",
            )
        ).block()

        // Mock Redis operations for deduplication
        val valueOps = io.mockk.mockk<org.springframework.data.redis.core.ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.empty()
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } returns Mono.just(true)

        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)
        every { listenerContainer.receive(any<ChannelTopic>()) } returns Flux.empty()
        coEvery { sandboxRunner.run(any(), any<SandboxInput>()) } answers {
            val input = secondArg<SandboxInput>()
            val sum = input.args.sumOf { (it as Number).toInt() }
            SandboxOutput(
                status = TestCaseStatus.PASSED,
                output = sum.toString(),
                error = null,
                timeMs = 1.2,
                memoryMb = 2.4,
            )
        }
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .build()
    }

    @Test
    fun `post submission triggers worker and updates mongodb status`() {
        val accepted = submitSamplePython()
        val completed = awaitCompleted(accepted.submissionId)

        assertEquals(SubmissionStatus.ACCEPTED, completed.status)
        assertEquals("anonymous", completed.submitterId)
        assertEquals("A00123", completed.submitterPublicCode)
        assertEquals(10, completed.testCaseResults.size)
        assertEquals(quiz101ExpectedOutputs, completed.testCaseResults.map { it.output })
        assertTrue(completed.testCaseResults.all { it.status == TestCaseStatus.PASSED })
    }

    @Test
    fun `post kotlin submission triggers worker and updates mongodb status`() {
        val accepted = submitSample(
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int): Int = a + b",
        )
        val completed = awaitCompleted(accepted.submissionId)

        assertEquals(SubmissionStatus.ACCEPTED, completed.status)
        assertEquals("anonymous", completed.submitterId)
        assertEquals("A00123", completed.submitterPublicCode)
        assertEquals(10, completed.testCaseResults.size)
        assertEquals(quiz101ExpectedOutputs, completed.testCaseResults.map { it.output })
        assertTrue(completed.testCaseResults.all { it.status == TestCaseStatus.PASSED })
    }

    @Test
    fun `post dart submission triggers worker and updates mongodb status`() {
        val accepted = submitSample(
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
        )
        val completed = awaitCompleted(accepted.submissionId)

        assertEquals(SubmissionStatus.ACCEPTED, completed.status)
        assertEquals("anonymous", completed.submitterId)
        assertEquals("A00123", completed.submitterPublicCode)
        assertEquals(10, completed.testCaseResults.size)
        assertEquals(quiz101ExpectedOutputs, completed.testCaseResults.map { it.output })
        assertTrue(completed.testCaseResults.all { it.status == TestCaseStatus.PASSED })
    }

    @Test
    fun `three concurrent submissions are processed in parallel`() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        coEvery { sandboxRunner.run(Language.PYTHON, any<SandboxInput>()) } coAnswers {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { prev -> max(prev, now) }
            try {
                delay(300)
                val input = secondArg<SandboxInput>()
                val sum = input.args.sumOf { (it as Number).toInt() }
                SandboxOutput(
                    status = TestCaseStatus.PASSED,
                    output = sum.toString(),
                    error = null,
                    timeMs = 5.0,
                    memoryMb = 3.0,
                )
            } finally {
                active.decrementAndGet()
            }
        }

        val ids = (1..3).map { submitSamplePython().submissionId }
        val completed = ids.map { awaitCompleted(it) }

        assertTrue(completed.all { it.status == SubmissionStatus.ACCEPTED })
        assertTrue(maxActive.get() >= 2, "expected parallel execution but maxActive=${maxActive.get()}")
    }

    private fun submitSamplePython(): SubmissionAccepted =
        submitSample(
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )

    private fun submitSample(
        language: Language,
        code: String,
    ): SubmissionAccepted =
        webTestClient.post()
            .uri("/v1/submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "publicCode": "A00123",
                  "problemId": "quiz-101",
                  "language": "${language.name}",
                  "code": ${objectMapper.writeValueAsString(code)}
                }
                """.trimIndent()
            )
            .exchange()
            .expectStatus().isAccepted
            .expectBody(SubmissionAccepted::class.java)
            .returnResult()
            .responseBody
            ?: error("missing SubmissionAccepted response")

    private fun awaitCompleted(submissionId: String, timeoutMs: Long = 10_000): Submission {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = submissionRepository.findById(submissionId).block()
            if (current != null &&
                current.status != SubmissionStatus.PENDING &&
                current.status != SubmissionStatus.RUNNING
            ) {
                return current
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for completion: $submissionId")
    }

    companion object {
        private val quiz101ExpectedOutputs = listOf("8", "12", "0", "-3", "350", "0", "1000", "100", "-42", "5555")

        @Container
        @ServiceConnection
        @JvmStatic
        val mongo: MongoDBContainer = MongoDBContainer("mongo:8.0")
    }
}
