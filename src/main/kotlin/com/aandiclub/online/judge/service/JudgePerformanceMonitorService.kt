package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque

data class JudgePerformanceSnapshot(
    val feature: String,
    val lastUpdatedAt: Instant,
    val queuedSubmissions: Int,
    val activeSubmissions: Int,
    val activeTestCases: Int,
    val configuredCaseConcurrency: Int,
    val runningByLanguage: Map<String, Int>,
    val totals: JudgePerformanceTotals,
    val recent: JudgePerformanceRecent,
)

data class JudgePerformanceTotals(
    val submissionsAccepted: Long,
    val submissionsStarted: Long,
    val submissionsCompleted: Long,
    val submissionsFailed: Long,
    val testCasesStarted: Long,
    val testCasesCompleted: Long,
    val verdictCounts: Map<String, Long>,
)

data class JudgePerformanceRecent(
    val sampleSize: Int,
    val averageFirstResultMs: Double,
    val p95FirstResultMs: Double,
    val averageJudgeDurationMs: Double,
    val p95JudgeDurationMs: Double,
    val averageCaseRuntimeMs: Double,
    val p95CaseRuntimeMs: Double,
)

@Service
class JudgePerformanceMonitorService(
    private val clock: Clock,
    private val sandboxProperties: SandboxProperties,
) {
    private val lock = Any()
    private val trackedSubmissions = linkedMapOf<String, TrackedSubmission>()
    private val firstResultSamplesMs = ArrayDeque<Long>()
    private val judgeDurationSamplesMs = ArrayDeque<Long>()
    private val caseRuntimeSamplesMs = ArrayDeque<Long>()

    private var activeTestCases: Int = 0
    private var submissionsAccepted: Long = 0
    private var submissionsStarted: Long = 0
    private var submissionsCompleted: Long = 0
    private var submissionsFailed: Long = 0
    private var testCasesStarted: Long = 0
    private var testCasesCompleted: Long = 0
    private val verdictCounts: MutableMap<String, Long> = linkedMapOf()

    private val judgePerformanceFlow = MutableStateFlow(buildSnapshot())

    fun stream(feature: String): Flow<JudgePerformanceSnapshot> = when (normalizeFeature(feature)) {
        JUDGE_PERFORMANCE_FEATURE -> judgePerformanceFlow
        else -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown monitor feature: $feature")
    }

    fun currentJudgePerformance(): JudgePerformanceSnapshot = judgePerformanceFlow.value

    fun currentAll(): Map<String, Any> = linkedMapOf(
        JUDGE_PERFORMANCE_FEATURE to currentJudgePerformance(),
    )

    fun onSubmissionAccepted(submission: Submission) {
        synchronized(lock) {
            submissionsAccepted += 1
            trackedSubmissions[submission.id] = TrackedSubmission(
                submissionId = submission.id,
                language = submission.language,
                acceptedAt = clock.instant(),
            )
            publishLocked()
        }
    }

    fun onSubmissionStarted(submission: Submission, testCaseCount: Int) {
        synchronized(lock) {
            val tracked = trackedSubmissions.getOrPut(submission.id) {
                TrackedSubmission(
                    submissionId = submission.id,
                    language = submission.language,
                    acceptedAt = clock.instant(),
                )
            }
            if (tracked.startedAt == null) {
                tracked.startedAt = clock.instant()
                tracked.totalCases = testCaseCount
                submissionsStarted += 1
                publishLocked()
            }
        }
    }

    fun onCaseStarted(submissionId: String) {
        synchronized(lock) {
            activeTestCases += 1
            testCasesStarted += 1
            publishLocked()
        }
    }

    fun onCaseFinished(submissionId: String, result: TestCaseResult) {
        synchronized(lock) {
            val tracked = trackedSubmissions[submissionId]
            val now = clock.instant()

            activeTestCases = (activeTestCases - 1).coerceAtLeast(0)
            testCasesCompleted += 1
            appendSample(caseRuntimeSamplesMs, result.timeMs.toLong().coerceAtLeast(0))

            if (tracked != null && tracked.startedAt != null && tracked.firstResultAt == null) {
                tracked.firstResultAt = now
                appendSample(
                    firstResultSamplesMs,
                    (now.toEpochMilli() - tracked.startedAt!!.toEpochMilli()).coerceAtLeast(0),
                )
            }

            publishLocked()
        }
    }

    fun onSubmissionCompleted(submission: Submission, status: SubmissionStatus) {
        synchronized(lock) {
            val tracked = trackedSubmissions.remove(submission.id)
            val now = clock.instant()

            submissionsCompleted += 1
            verdictCounts[status.name] = verdictCounts.getOrDefault(status.name, 0L) + 1L

            val startedAt = tracked?.startedAt
            if (startedAt != null) {
                appendSample(
                    judgeDurationSamplesMs,
                    (now.toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0),
                )
            }

            publishLocked()
        }
    }

    fun onSubmissionFailed(submissionId: String, status: SubmissionStatus = SubmissionStatus.RUNTIME_ERROR) {
        synchronized(lock) {
            trackedSubmissions.remove(submissionId)
            submissionsFailed += 1
            verdictCounts[status.name] = verdictCounts.getOrDefault(status.name, 0L) + 1L
            publishLocked()
        }
    }

    private fun publishLocked() {
        judgePerformanceFlow.value = buildSnapshot()
    }

    private fun buildSnapshot(): JudgePerformanceSnapshot {
        val now = clock.instant()
        val queued = trackedSubmissions.values.count { it.startedAt == null }
        val active = trackedSubmissions.values.count { it.startedAt != null }
        val runningByLanguage = trackedSubmissions.values
            .asSequence()
            .filter { it.startedAt != null }
            .groupingBy { it.language.value }
            .eachCount()
            .toSortedMap()

        return JudgePerformanceSnapshot(
            feature = JUDGE_PERFORMANCE_FEATURE,
            lastUpdatedAt = now,
            queuedSubmissions = queued,
            activeSubmissions = active,
            activeTestCases = activeTestCases,
            configuredCaseConcurrency = sandboxProperties.caseConcurrency.coerceAtLeast(1),
            runningByLanguage = runningByLanguage,
            totals = JudgePerformanceTotals(
                submissionsAccepted = submissionsAccepted,
                submissionsStarted = submissionsStarted,
                submissionsCompleted = submissionsCompleted,
                submissionsFailed = submissionsFailed,
                testCasesStarted = testCasesStarted,
                testCasesCompleted = testCasesCompleted,
                verdictCounts = verdictCounts.toSortedMap(),
            ),
            recent = JudgePerformanceRecent(
                sampleSize = maxOf(
                    firstResultSamplesMs.size,
                    judgeDurationSamplesMs.size,
                    caseRuntimeSamplesMs.size,
                ),
                averageFirstResultMs = average(firstResultSamplesMs),
                p95FirstResultMs = percentile95(firstResultSamplesMs),
                averageJudgeDurationMs = average(judgeDurationSamplesMs),
                p95JudgeDurationMs = percentile95(judgeDurationSamplesMs),
                averageCaseRuntimeMs = average(caseRuntimeSamplesMs),
                p95CaseRuntimeMs = percentile95(caseRuntimeSamplesMs),
            ),
        )
    }

    private fun normalizeFeature(feature: String): String = when (feature.lowercase()) {
        JUDGE_PERFORMANCE_FEATURE,
        "judge",
        "judge-performance",
        "submission-performance",
        "submissions",
        -> JUDGE_PERFORMANCE_FEATURE
        else -> feature.lowercase()
    }

    private fun appendSample(samples: ArrayDeque<Long>, value: Long) {
        samples.addLast(value)
        while (samples.size > MAX_SAMPLE_SIZE) {
            samples.removeFirst()
        }
    }

    private fun average(samples: ArrayDeque<Long>): Double =
        if (samples.isEmpty()) 0.0 else samples.average()

    private fun percentile95(samples: ArrayDeque<Long>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        val index = ((sorted.size - 1) * 0.95).toInt()
        return sorted[index].toDouble()
    }

    private data class TrackedSubmission(
        val submissionId: String,
        val language: Language,
        val acceptedAt: Instant,
        var startedAt: Instant? = null,
        var firstResultAt: Instant? = null,
        var totalCases: Int = 0,
    )

    companion object {
        private const val MAX_SAMPLE_SIZE = 128
        const val JUDGE_PERFORMANCE_FEATURE: String = "judge-performance"
    }
}
