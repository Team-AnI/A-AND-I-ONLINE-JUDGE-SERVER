package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.api.dto.AdminTestCaseRecord
import com.aandiclub.online.judge.api.dto.ProblemTestCaseRecord
import com.aandiclub.online.judge.api.v2.V2AdminSubmissionController
import com.aandiclub.online.judge.api.v2.V2AdminTestCaseController
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.service.ProblemService
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class V2AdminControllersTest {
    private val submissionService = mockk<SubmissionService>()
    private val problemService = mockk<ProblemService>()
    private val submissionController = V2AdminSubmissionController(submissionService)
    private val testCaseController = V2AdminTestCaseController(problemService)

    @Test
    fun `v2 admin submissions wraps response`() = runTest {
        coEvery { submissionService.getAllSubmissions() } returns listOf(
            AdminSubmissionRecord(
                submissionId = "sub-1",
                submitterId = "user-1",
                submitterPublicCode = "A00123",
                problemId = "quiz-101",
                language = Language.PYTHON,
                code = "print(1)",
                status = SubmissionStatus.ACCEPTED,
                testCases = emptyList(),
                createdAt = OffsetDateTime.parse("2026-03-15T19:00:00+09:00"),
                completedAt = OffsetDateTime.parse("2026-03-15T19:00:01+09:00"),
            )
        )

        val response = submissionController.getAllSubmissions()

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.success)
        assertEquals("sub-1", response.body?.data?.first()?.submissionId)
        coVerify(exactly = 1) { submissionService.getAllSubmissions() }
    }

    @Test
    fun `v2 admin testcases wraps response`() = runTest {
        coEvery { problemService.getAllProblemsWithTestCases() } returns listOf(
            ProblemTestCaseRecord(
                problemId = "quiz-101",
                testCases = listOf(
                    AdminTestCaseRecord(
                        caseId = 1,
                        args = listOf(1, 2),
                        argTypes = listOf("INTEGER", "INTEGER"),
                        expectedOutput = 3,
                        expectedOutputType = "INTEGER",
                    )
                ),
                updatedAt = OffsetDateTime.parse("2026-03-15T19:00:00+09:00"),
            )
        )

        val response = testCaseController.getAllTestCases()

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.success)
        assertEquals("quiz-101", response.body?.data?.first()?.problemId)
        coVerify(exactly = 1) { problemService.getAllProblemsWithTestCases() }
    }
}
