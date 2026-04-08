package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.config.SubmissionEventProperties
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.domain.TestCaseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Service
@ConditionalOnProperty(prefix = "judge.submission-events", name = ["publishEnabled"], havingValue = "true")
class SubmissionEventPublisher(
    @Qualifier("submissionSnsClient") private val snsClient: SnsClient,
    private val properties: SubmissionEventProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SubmissionEventPublisher::class.java)

    suspend fun publishJudgeCompleted(
        publicCode: String,
        problemId: String,
        testCases: List<TestCase>,
        results: List<TestCaseResult>,
    ) {
        if (properties.topicArn.isBlank()) {
            log.warn("Judge completed event publishing skipped: topicArn is blank")
            return
        }

        val passedCases = results.count { it.status == TestCaseStatus.PASSED }
        val totalCases = testCases.size
        val score = calculateScore(testCases, results)

        val event = mapOf(
            "eventType" to "JUDGE_COMPLETED",
            "publicCode" to publicCode,
            "problemId" to problemId,
            "score" to score,
            "passedCases" to passedCases,
            "totalCases" to totalCases,
            "timestamp" to Instant.now().toString(),
        )

        val message = objectMapper.writeValueAsString(event)

        withContext(Dispatchers.IO) {
            try {
                val request = PublishRequest.builder()
                    .topicArn(properties.topicArn)
                    .message(message)
                    .build()
                val response = snsClient.publish(request)
                log.info(
                    "Judge completed event published: publicCode={}, problemId={}, score={}, messageId={}",
                    publicCode, problemId, score, response.messageId(),
                )
            } catch (ex: Exception) {
                log.error("Failed to publish judge completed event: publicCode={}, problemId={}", publicCode, problemId, ex)
            }
        }
    }

    private fun calculateScore(testCases: List<TestCase>, results: List<TestCaseResult>): Int {
        if (testCases.isEmpty()) return 0
        val totalScore = testCases.sumOf { it.score }
        return if (totalScore == 0) {
            results.count { it.status == TestCaseStatus.PASSED } * 100 / testCases.size
        } else {
            val resultMap = results.associateBy { it.caseId }
            val passedScore = testCases
                .filter { tc -> resultMap[tc.caseId]?.status == TestCaseStatus.PASSED }
                .sumOf { it.score }
            passedScore * 100 / totalScore
        }
    }
}
