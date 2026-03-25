package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.domain.Problem
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.repository.ProblemRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Instant
import java.time.OffsetDateTime

class ProblemServiceTest {
    private val problemRepository = mockk<ProblemRepository>()
    private val service = ProblemService(problemRepository)

    @Test
    fun `getAllProblemsWithTestCases includes inferred value types`() = runTest {
        every { problemRepository.findAll() } returns Flux.just(
            Problem(
                problemId = "quiz-typed",
                testCases = listOf(
                    TestCase(
                        caseId = 1,
                        args = listOf(1, 2.5, "three", true, null),
                        expectedOutput = mapOf("sum" to 3),
                    )
                ),
                updatedAt = Instant.parse("2026-03-24T00:00:00Z"),
            )
        )

        val result = service.getAllProblemsWithTestCases()

        assertEquals(1, result.size)
        assertEquals(listOf("INTEGER", "DECIMAL", "STRING", "BOOLEAN", "NULL"), result[0].testCases[0].argTypes)
        assertEquals("OBJECT", result[0].testCases[0].expectedOutputType)
        assertEquals(OffsetDateTime.parse("2026-03-24T09:00:00+09:00"), result[0].updatedAt)
    }
}
