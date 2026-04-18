package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.api.dto.AdminTestCaseRecord
import com.aandiclub.online.judge.api.dto.ProblemTestCaseRecord
import com.aandiclub.online.judge.api.v2.V2AdminMonitorController
import com.aandiclub.online.judge.api.v2.V2AdminSubmissionController
import com.aandiclub.online.judge.api.v2.V2AdminTestCaseController
import com.aandiclub.online.judge.api.v2.V2MonitorController
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.service.JudgePerformanceMonitorService
import com.aandiclub.online.judge.service.JudgePerformanceRecent
import com.aandiclub.online.judge.service.JudgePerformanceSnapshot
import com.aandiclub.online.judge.service.JudgePerformanceTotals
import com.aandiclub.online.judge.service.ProblemService
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime

class V2AdminControllersTest {
    private val submissionService = mockk<SubmissionService>()
    private val problemService = mockk<ProblemService>()
    private val judgePerformanceMonitorService = mockk<JudgePerformanceMonitorService>()
    private val submissionController = V2AdminSubmissionController(submissionService)
    private val testCaseController = V2AdminTestCaseController(problemService)
    private val objectMapper = ObjectMapper()
    private val monitorController = V2AdminMonitorController(judgePerformanceMonitorService, objectMapper)
    private val overviewController = V2MonitorController(judgePerformanceMonitorService, objectMapper)

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

    @Test
    fun `v2 admin monitor wraps judge-performance sse response`() = runTest {
        every { judgePerformanceMonitorService.stream("judge-performance") } returns flowOf(
            JudgePerformanceSnapshot(
                feature = "judge-performance",
                lastUpdatedAt = Instant.parse("2026-04-18T12:40:13Z"),
                queuedSubmissions = 1,
                activeSubmissions = 2,
                activeTestCases = 3,
                configuredCaseConcurrency = 4,
                runningByLanguage = mapOf("python" to 2),
                totals = JudgePerformanceTotals(
                    submissionsAccepted = 10,
                    submissionsStarted = 9,
                    submissionsCompleted = 8,
                    submissionsFailed = 1,
                    testCasesStarted = 30,
                    testCasesCompleted = 27,
                    verdictCounts = mapOf("ACCEPTED" to 6, "WRONG_ANSWER" to 2),
                ),
                recent = JudgePerformanceRecent(
                    sampleSize = 8,
                    averageFirstResultMs = 12.5,
                    p95FirstResultMs = 20.0,
                    averageJudgeDurationMs = 45.0,
                    p95JudgeDurationMs = 60.0,
                    averageCaseRuntimeMs = 8.0,
                    p95CaseRuntimeMs = 13.0,
                ),
            )
        )

        val events = monitorController.streamFeature("judge-performance").toList()

        assertEquals(1, events.size)
        assertEquals("snapshot", events.first().event())
        val payload = events.first().data()
        assertNotNull(payload)
        val json = objectMapper.readTree(payload)
        assertEquals(true, json.path("success").asBoolean())
        assertEquals("snapshot", json.path("data").path("event").asText())
        assertEquals("judge-performance", json.path("data").path("feature").asText())
        assertEquals(2, json.path("data").path("payload").path("activeSubmissions").asInt())
    }

    @Test
    fun `v2 monitor returns all feature snapshots in one envelope`() {
        every { judgePerformanceMonitorService.currentAll() } returns linkedMapOf(
            "judge-performance" to JudgePerformanceSnapshot(
                feature = "judge-performance",
                lastUpdatedAt = Instant.parse("2026-04-18T12:40:13Z"),
                queuedSubmissions = 1,
                activeSubmissions = 2,
                activeTestCases = 3,
                configuredCaseConcurrency = 4,
                runningByLanguage = mapOf("python" to 2),
                totals = JudgePerformanceTotals(
                    submissionsAccepted = 10,
                    submissionsStarted = 9,
                    submissionsCompleted = 8,
                    submissionsFailed = 1,
                    testCasesStarted = 30,
                    testCasesCompleted = 27,
                    verdictCounts = mapOf("ACCEPTED" to 6),
                ),
                recent = JudgePerformanceRecent(
                    sampleSize = 8,
                    averageFirstResultMs = 12.5,
                    p95FirstResultMs = 20.0,
                    averageJudgeDurationMs = 45.0,
                    p95JudgeDurationMs = 60.0,
                    averageCaseRuntimeMs = 8.0,
                    p95CaseRuntimeMs = 13.0,
                ),
            )
        )

        val response = overviewController.getAll()

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.success)
        assertEquals(
            "judge-performance",
            response.body?.data?.features?.keys?.single(),
        )
        assertEquals(
            2,
            response.body?.data?.features?.get("judge-performance")?.path("activeSubmissions")?.asInt(),
        )
    }
}
