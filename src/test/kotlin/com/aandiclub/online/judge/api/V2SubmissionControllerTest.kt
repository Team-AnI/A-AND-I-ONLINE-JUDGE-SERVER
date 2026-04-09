package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.api.dto.SubmissionResult
import com.aandiclub.online.judge.api.v2.V2SubmissionController
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionRequest
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.api.v2.support.V2ExchangeAttributes
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.codec.ServerSentEvent
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import tools.jackson.databind.ObjectMapper

class V2SubmissionControllerTest {

    private val submissionService = mockk<SubmissionService>()
    private val objectMapper = ObjectMapper()
    private val controller = V2SubmissionController(submissionService, objectMapper)

    @Test
    fun `v2 submit wraps accepted response in envelope`() = runTest {
        val exchange = exchange("/v2/submissions")
        exchange.attributes[JwtExchangeAttributes.SUBJECT] = "user-1"
        exchange.attributes[V2ExchangeAttributes.DEVICE_OS] = "ANDROID"
        exchange.attributes[V2ExchangeAttributes.TIMESTAMP] = "1712600000"
        val request = V2SubmissionRequest(
            publicCode = "A00123",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
        )
        coEvery { submissionService.createSubmission(any(), "user-1") } returns SubmissionAccepted(
            submissionId = "sub-1",
            streamUrl = "/v1/submissions/sub-1/stream",
        )

        val response = controller.submit(request, exchange)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals(true, response.body?.success)
        assertEquals("sub-1", response.body?.data?.submissionId)
        assertEquals("/v2/submissions/sub-1/stream", response.body?.data?.streamUrl)
        coVerify(exactly = 1) { submissionService.createSubmission(any(), "user-1") }
    }

    @Test
    fun `v2 getResult returns conflict envelope when result is not ready`() = runTest {
        val exchange = exchange("/v2/submissions/sub-1")
        exchange.attributes[JwtExchangeAttributes.SUBJECT] = "user-1"
        coEvery { submissionService.getResult("sub-1", "user-1", false) } returns null

        val response = controller.getResult("sub-1", exchange)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(false, response.body?.success)
        assertEquals(V2ErrorCode.JUDGE_RESULT_NOT_READY.code, response.body?.error?.code)
    }

    @Test
    fun `v2 getResult returns wrapped data when submission exists`() = runTest {
        val exchange = exchange("/v2/submissions/sub-1")
        exchange.attributes[JwtExchangeAttributes.SUBJECT] = "user-1"
        coEvery { submissionService.getResult("sub-1", "user-1", false) } returns SubmissionResult(
            submissionId = "sub-1",
            status = SubmissionStatus.ACCEPTED,
            testCases = emptyList(),
        )

        val response = controller.getResult("sub-1", exchange)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(true, response.body?.success)
        assertEquals(SubmissionStatus.ACCEPTED, response.body?.data?.status)
    }

    @Test
    fun `v2 stream wraps SSE payloads in envelope`() = runTest {
        val exchange = exchange("/v2/submissions/sub-1/stream")
        exchange.attributes[JwtExchangeAttributes.SUBJECT] = "user-1"
        val event = ServerSentEvent.builder<String>()
            .event("test_case_result")
            .data("""{"caseId":1,"status":"PASSED"}""")
            .build()
        coEvery { submissionService.streamResults("sub-1", "user-1", false) } returns flowOf(event)

        val flow = controller.streamResults("sub-1", exchange)
        val result = flow.toList()

        assertEquals(1, result.size)
        assertEquals("test_case_result", result.first().event())
        val payload = result.first().data()
        assertNotNull(payload)
        val json = objectMapper.readTree(payload)
        assertEquals(true, json.path("success").asBoolean())
        assertEquals("test_case_result", json.path("data").path("event").asText())
        assertEquals(1, json.path("data").path("payload").path("caseId").asInt())
    }

    private fun exchange(path: String): MockServerWebExchange =
        MockServerWebExchange.from(MockServerHttpRequest.get(path).build())
}
