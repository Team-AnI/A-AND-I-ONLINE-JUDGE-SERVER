package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.config.SubmissionEventProperties
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.domain.TestCaseStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import tools.jackson.databind.ObjectMapper

class SubmissionEventPublisherTest {
    private val snsClient = mockk<SnsClient>()
    private val objectMapper = ObjectMapper()
    private val testCases = listOf(TestCase(caseId = 1, args = listOf(1, 2), expectedOutput = 3, score = 100))
    private val results = listOf(TestCaseResult(caseId = 1, status = TestCaseStatus.PASSED))

    @Test
    fun `publish includes fifo fields for fifo topic`() = runTest {
        val requestSlot = slot<PublishRequest>()
        every { snsClient.publish(capture(requestSlot)) } returns PublishResponse.builder().messageId("msg-1").build()
        val publisher = SubmissionEventPublisher(
            snsClient = snsClient,
            properties = SubmissionEventProperties(
                publishEnabled = true,
                topicArn = "arn:aws:sns:ap-northeast-2:123456789012:judge-submission-events.fifo",
            ),
            objectMapper = objectMapper,
        )

        publisher.publishJudgeCompleted(
            submissionId = "sub-123",
            publicCode = "A00123",
            problemId = "quiz-101",
            testCases = testCases,
            results = results,
        )

        assertEquals("quiz-101", requestSlot.captured.messageGroupId())
        assertEquals("sub-123", requestSlot.captured.messageDeduplicationId())
    }

    @Test
    fun `publish omits fifo fields for standard topic`() = runTest {
        val requestSlot = slot<PublishRequest>()
        every { snsClient.publish(capture(requestSlot)) } returns PublishResponse.builder().messageId("msg-1").build()
        val publisher = SubmissionEventPublisher(
            snsClient = snsClient,
            properties = SubmissionEventProperties(
                publishEnabled = true,
                topicArn = "arn:aws:sns:ap-northeast-2:123456789012:judge-submission-events",
            ),
            objectMapper = objectMapper,
        )

        publisher.publishJudgeCompleted(
            submissionId = "sub-456",
            publicCode = "A00123",
            problemId = "quiz-101",
            testCases = testCases,
            results = results,
        )

        assertNull(requestSlot.captured.messageGroupId())
        assertNull(requestSlot.captured.messageDeduplicationId())
    }
}
