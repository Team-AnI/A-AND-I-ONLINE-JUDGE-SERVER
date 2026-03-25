package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class AdminSubmissionControllerTest {
    private val submissionService = mockk<SubmissionService>()
    private val controller = AdminSubmissionController(submissionService)

    @Test
    fun `getAllSubmissions returns all admin submission records`() = runTest {
        val records = listOf(
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
                completedAt = OffsetDateTime.parse("2026-03-15T19:00:01+09:00"),
            )
        )
        coEvery { submissionService.getAllSubmissions() } returns records

        val response = controller.getAllSubmissions()

        assertEquals(200, response.statusCode.value())
        assertEquals(records, response.body)
        coVerify(exactly = 1) { submissionService.getAllSubmissions() }
    }
}
