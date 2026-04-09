package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.MyProblemSubmissionRecord
import com.aandiclub.online.judge.api.v2.V2ProblemSubmissionController
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

class V2ProblemSubmissionControllerTest {
    private val submissionService = mockk<SubmissionService>()
    private val controller = V2ProblemSubmissionController(submissionService)

    @Test
    fun `v2 problem submissions wraps list response`() = runTest {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v2/problems/quiz-101/submissions/me").build())
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
        assertEquals(true, response.body?.success)
        assertEquals(records.first().submissionId, response.body?.data?.first()?.submissionId)
        coVerify(exactly = 1) { submissionService.getProblemSubmissions("quiz-101", "user-1") }
    }
}
