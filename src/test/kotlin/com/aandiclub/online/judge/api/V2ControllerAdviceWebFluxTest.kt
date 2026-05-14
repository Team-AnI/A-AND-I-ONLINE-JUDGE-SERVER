package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.v2.V2ControllerAdvice
import com.aandiclub.online.judge.api.v2.V2SubmissionController
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper

class V2ControllerAdviceWebFluxTest {
    private val submissionService = mockk<SubmissionService>()
    private val webTestClient = WebTestClient
        .bindToController(V2SubmissionController(submissionService, ObjectMapper()))
        .controllerAdvice(V2ControllerAdvice())
        .build()

    @Test
    fun `validation failure returns v2 envelope`() {
        val body = webTestClient.post()
            .uri("/v2/submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"publicCode":"","problemId":"quiz-101","language":"PYTHON","code":"print(1)"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .returnResult()
            .responseBody

        val json = ObjectMapper().readTree(body)
        assertEquals(false, json.path("success").asBoolean())
        assertEquals(53001, json.path("error").path("code").asInt())
    }

    @Test
    fun `unsupported language returns language error code`() {
        val body = webTestClient.post()
            .uri("/v2/submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"publicCode":"A00123","problemId":"quiz-101","language":"RUBY","code":"puts 1"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .returnResult()
            .responseBody

        val json = ObjectMapper().readTree(body)
        assertEquals(false, json.path("success").asBoolean())
        assertEquals(54302, json.path("error").path("code").asInt())
    }

    @Test
    fun `service not found error returns v2 envelope`() {
        coEvery { submissionService.createSubmission(any(), any()) } throws ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "User not found with publicCode: A00123",
        )

        val body = webTestClient.post()
            .uri("/v2/submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"publicCode":"A00123","problemId":"quiz-101","language":"PYTHON","code":"print(1)"}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .returnResult()
            .responseBody

        val json = ObjectMapper().readTree(body)
        assertEquals(false, json.path("success").asBoolean())
        assertEquals(55002, json.path("error").path("code").asInt())
    }

    @Test
    fun `response status 400 maps to judge validation code`() {
        coEvery { submissionService.createSubmission(any(), any()) } throws ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "bad request",
        )

        val json = postSubmissionAndRead(HttpStatus.BAD_REQUEST)

        assertEquals(false, json.path("success").asBoolean())
        assertEquals(true, json.path("data").isNull)
        assertEquals(53001, json.path("error").path("code").asInt())
        assertEquals("request", json.path("error").path("value").asText())
        assertEquals(false, json.path("timestamp").asText().isBlank())
    }

    @Test
    fun `response status 409 maps to judge business code`() {
        coEvery { submissionService.createSubmission(any(), any()) } throws ResponseStatusException(
            HttpStatus.CONFLICT,
            "submission cannot run now",
        )

        val json = postSubmissionAndRead(HttpStatus.CONFLICT)

        assertEquals(false, json.path("success").asBoolean())
        assertEquals(true, json.path("data").isNull)
        assertEquals(54001, json.path("error").path("code").asInt())
        assertEquals("submission", json.path("error").path("value").asText())
    }

    @Test
    fun `response status 500 maps to judge internal code`() {
        coEvery { submissionService.createSubmission(any(), any()) } throws ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "database unavailable",
        )

        val json = postSubmissionAndRead(HttpStatus.INTERNAL_SERVER_ERROR)

        assertEquals(false, json.path("success").asBoolean())
        assertEquals(true, json.path("data").isNull)
        assertEquals(54801, json.path("error").path("code").asInt())
        assertEquals("judge", json.path("error").path("value").asText())
    }

    private fun postSubmissionAndRead(status: HttpStatus) =
        ObjectMapper().readTree(
            webTestClient.post()
                .uri("/v2/submissions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"publicCode":"A00123","problemId":"quiz-101","language":"PYTHON","code":"print(1)"}""")
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectBody()
                .returnResult()
                .responseBody
        )
}
