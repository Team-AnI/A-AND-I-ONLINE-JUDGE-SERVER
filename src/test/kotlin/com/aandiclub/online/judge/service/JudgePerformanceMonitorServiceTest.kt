package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.domain.TestCaseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

class JudgePerformanceMonitorServiceTest {

    private val service = JudgePerformanceMonitorService(
        clock = Clock.systemUTC(),
        sandboxProperties = SandboxProperties(caseConcurrency = 4),
    )

    @Test
    fun `records queued active and recent judge metrics across lifecycle`() {
        val submission = Submission(
            id = "sub-1",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
        )

        service.onSubmissionAccepted(submission)
        assertEquals(1, service.currentJudgePerformance().queuedSubmissions)

        service.onSubmissionStarted(submission, testCaseCount = 2)
        service.onCaseStarted(submission.id)
        Thread.sleep(5)
        service.onCaseFinished(
            submission.id,
            TestCaseResult(caseId = 1, status = TestCaseStatus.PASSED, timeMs = 7.0, memoryMb = 1.0),
        )
        service.onCaseStarted(submission.id)
        service.onCaseFinished(
            submission.id,
            TestCaseResult(caseId = 2, status = TestCaseStatus.PASSED, timeMs = 11.0, memoryMb = 1.0),
        )
        service.onSubmissionCompleted(submission, SubmissionStatus.ACCEPTED)

        val snapshot = service.currentJudgePerformance()
        assertEquals(0, snapshot.queuedSubmissions)
        assertEquals(0, snapshot.activeSubmissions)
        assertEquals(0, snapshot.activeTestCases)
        assertEquals(1L, snapshot.totals.submissionsAccepted)
        assertEquals(1L, snapshot.totals.submissionsStarted)
        assertEquals(1L, snapshot.totals.submissionsCompleted)
        assertEquals(2L, snapshot.totals.testCasesCompleted)
        assertEquals(1L, snapshot.totals.verdictCounts["ACCEPTED"])
        assertTrue(snapshot.recent.averageFirstResultMs >= 0.0)
        assertTrue(snapshot.recent.averageJudgeDurationMs >= 0.0)
        assertTrue(snapshot.recent.averageCaseRuntimeMs >= 7.0)
    }

    @Test
    fun `records failed submissions and removes them from live counts`() {
        val submission = Submission(
            id = "sub-2",
            problemId = "quiz-101",
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int): Int = a + b",
        )

        service.onSubmissionAccepted(submission)
        service.onSubmissionStarted(submission, testCaseCount = 1)
        service.onSubmissionFailed(submission.id)

        val snapshot = service.currentJudgePerformance()
        assertEquals(0, snapshot.queuedSubmissions)
        assertEquals(0, snapshot.activeSubmissions)
        assertEquals(1L, snapshot.totals.submissionsFailed)
        assertEquals(1L, snapshot.totals.verdictCounts["RUNTIME_ERROR"])
    }
}
