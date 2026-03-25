package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.api.dto.AdminTestCaseRecord
import com.aandiclub.online.judge.api.dto.ProblemTestCaseRecord
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.service.ProblemService
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.OffsetDateTime

class ApiDateTimeSerializationTest {
    private val submissionService = mockk<SubmissionService>()
    private val problemService = mockk<ProblemService>()
    private val adminSubmissionClient = WebTestClient
        .bindToController(AdminSubmissionController(submissionService))
        .build()
    private val adminTestCaseClient = WebTestClient
        .bindToController(AdminTestCaseController(problemService))
        .build()

    @Test
    fun `admin submission timestamps are serialized in kst`() {
        coEvery { submissionService.getAllSubmissions() } returns listOf(
            AdminSubmissionRecord(
                submissionId = "sub-1",
                submitterId = "user-1",
                submitterPublicCode = "A00123",
                problemId = "quiz-101",
                language = Language.KOTLIN,
                code = "fun solution(a: Int, b: Int): Int = a + b",
                status = SubmissionStatus.ACCEPTED,
                testCases = emptyList(),
                createdAt = OffsetDateTime.parse("2026-03-15T19:00:00+09:00"),
                completedAt = OffsetDateTime.parse("2026-03-15T19:00:02+09:00"),
            )
        )

        adminSubmissionClient.get()
            .uri("/v1/admin/submissions")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].createdAt").isEqualTo("2026-03-15T19:00:00+09:00")
            .jsonPath("$[0].completedAt").isEqualTo("2026-03-15T19:00:02+09:00")
    }

    @Test
    fun `admin test case timestamps are serialized in kst`() {
        coEvery { problemService.getAllProblemsWithTestCases() } returns listOf(
            ProblemTestCaseRecord(
                problemId = "quiz-101",
                testCases = listOf(
                    AdminTestCaseRecord(
                        caseId = 1,
                        args = listOf(3, 5),
                        argTypes = listOf("INTEGER", "INTEGER"),
                        expectedOutput = 8,
                        expectedOutputType = "INTEGER",
                    )
                ),
                updatedAt = OffsetDateTime.parse("2026-03-15T19:00:00+09:00"),
            )
        )

        adminTestCaseClient.get()
            .uri("/v1/admin/testcases")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].updatedAt").isEqualTo("2026-03-15T19:00:00+09:00")
    }
}
