package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.MyProblemSubmissionRecord
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.time.OffsetDateTime

class ProblemSubmissionControllerTest {
    private val submissionService = mockk<SubmissionService>()
    private val controller = ProblemSubmissionController(submissionService)

    @Test
    fun `getMyProblemSubmissions delegates using jwt subject`() = runTest {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/problems/quiz-101/submissions/me").build())
        exchange.attributes[JwtExchangeAttributes.SUBJECT] = "user-1"

        val records = listOf(
            MyProblemSubmissionRecord(
                submissionId = "sub-1",
                problemId = "quiz-101",
                language = Language.KOTLIN,
                status = SubmissionStatus.ACCEPTED,
                testCases = emptyList(),
                createdAt = OffsetDateTime.parse("2026-03-15T19:00:00+09:00"),
                completedAt = OffsetDateTime.parse("2026-03-15T19:00:01+09:00"),
            )
        )
        coEvery { submissionService.getProblemSubmissions("quiz-101", "user-1") } returns records

        val response = controller.getMyProblemSubmissions("quiz-101", exchange)

        assertEquals(200, response.statusCode.value())
        assertEquals(records, response.body)
        coVerify(exactly = 1) { submissionService.getProblemSubmissions("quiz-101", "user-1") }
    }
}
