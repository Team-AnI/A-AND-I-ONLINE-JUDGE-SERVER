package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.config.ProblemEventProperties
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
@ConditionalOnProperty(prefix = "judge.problem-events", name = ["publish-enabled"], havingValue = "true")
class TestCaseEventPublisher(
    @Qualifier("problemSnsClient") private val snsClient: SnsClient,
    private val properties: ProblemEventProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(TestCaseEventPublisher::class.java)

    suspend fun publishTestCaseUpdated(problemId: String, testCaseCount: Int) {
        if (properties.topicArn.isBlank()) {
            log.warn("Test case event publishing skipped: topicArn is blank")
            return
        }

        val event = mapOf(
            "eventType" to "TEST_CASE_UPDATED",
            "problemId" to problemId,
            "testCaseCount" to testCaseCount,
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
                    "Test case updated event published: problemId={}, testCaseCount={}, messageId={}",
                    problemId,
                    testCaseCount,
                    response.messageId()
                )
            } catch (ex: Exception) {
                log.error("Failed to publish test case updated event: problemId={}", problemId, ex)
            }
        }
    }
}
