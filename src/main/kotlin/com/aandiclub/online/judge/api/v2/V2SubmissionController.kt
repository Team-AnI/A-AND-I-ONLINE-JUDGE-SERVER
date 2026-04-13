package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.requestAccess
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionAcceptedData
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionRequest
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionResultData
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionStreamEventData
import com.aandiclub.online.judge.api.v2.dto.toV1
import com.aandiclub.online.judge.api.v2.dto.toV2
import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.api.v2.support.v2RequestContext
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.service.SubmissionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v2/submissions")
@Tag(name = "Submissions V2", description = "A&I v2 submission APIs.")
class V2SubmissionController(
    private val submissionService: SubmissionService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(V2SubmissionController::class.java)

    @PostMapping
    @Operation(
        summary = "Create a new v2 submission",
        description = "Validates and stores a submission, starts asynchronous judging, and returns an A&I v2 envelope with a submissionId and SSE stream URL. Swagger UI includes ready-to-run examples for Python, Kotlin, and Dart using `quiz-101`.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "202",
                description = "Submission accepted and queued for judging.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        examples = [
                            ExampleObject(
                                name = "accepted",
                                value = """{"success":true,"data":{"submissionId":"2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972","streamUrl":"/v2/submissions/2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972/stream"},"error":null,"timestamp":"2026-04-09T02:15:30.123Z"}""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "400", description = "Validation failed for the request payload."),
            ApiResponse(responseCode = "401", description = "Missing or invalid Authenticate header."),
            ApiResponse(responseCode = "429", description = "Submission rate limit exceeded."),
        ],
    )
    suspend fun submit(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "A&I v2 code submission payload. Use `quiz-101` for quick Swagger testing and send source code for one supported language.",
            required = true,
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = V2SubmissionRequest::class),
                    examples = [
                        ExampleObject(
                            name = "python-sum",
                            summary = "Python solution for quiz-101",
                            value = """{"publicCode":"A00123","problemId":"quiz-101","language":"PYTHON","code":"def solution(a, b):\n    return a + b","options":{"realtimeFeedback":true}}""",
                        ),
                        ExampleObject(
                            name = "kotlin-sum",
                            summary = "Kotlin solution for quiz-101",
                            value = """{"publicCode":"A00123","problemId":"quiz-101","language":"KOTLIN","code":"fun solution(a: Int, b: Int): Int = a + b","options":{"realtimeFeedback":true}}""",
                        ),
                        ExampleObject(
                            name = "dart-sum",
                            summary = "Dart solution for quiz-101",
                            value = """{"publicCode":"A00123","problemId":"quiz-101","language":"DART","code":"int solution(int a, int b) => a + b;","options":{"realtimeFeedback":true}}""",
                        ),
                    ],
                ),
            ],
        )
        @Valid @RequestBody request: V2SubmissionRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<V2SubmissionAcceptedData>> {
        val access = exchange.requestAccess()
        val context = exchange.v2RequestContext()
        val accepted = submissionService.createSubmission(request.toV1(), access.submitterId)
        val data = accepted.toV2(streamUrl = "/v2/submissions/${accepted.submissionId}/stream")
        SubmissionMdc.withSubmissionId(accepted.submissionId) {
            log.info(
                "V2 submission request accepted: submitterId={}, problemId={}, language={}, deviceOS={}, clientTimestamp={}",
                access.submitterId,
                request.problemId,
                request.language,
                context.deviceOS,
                context.timestamp,
            )
        }
        return ResponseEntity.accepted().body(V2ApiResponses.success(data))
    }

    @GetMapping("/{submissionId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Stream live v2 judge events",
        description = "Subscribes to a text/event-stream feed for a submission. The stream emits wrapped `test_case_result`, `done`, and `error` events in real time. In Swagger UI, create a submission first, copy the returned `submissionId`, and then call this endpoint.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "SSE stream opened successfully.",
                content = [
                    Content(
                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                        examples = [
                            ExampleObject(
                                name = "stream-events",
                                value = "event: test_case_result\ndata: {\"success\":true,\"data\":{\"event\":\"test_case_result\",\"payload\":{\"caseId\":1,\"status\":\"PASSED\",\"timeMs\":1.37,\"memoryMb\":12.4,\"output\":\"비공개\",\"error\":null}},\"error\":null,\"timestamp\":\"2026-04-09T02:15:30.123Z\"}",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid Authenticate header."),
        ],
    )
    suspend fun streamResults(
        @Parameter(
            description = "Submission identifier returned by the create submission API.",
            example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
        )
        @PathVariable submissionId: String,
        exchange: ServerWebExchange,
    ): Flow<ServerSentEvent<String>> {
        val access = exchange.requestAccess()
        return submissionService.streamResults(submissionId, access.submitterId, access.isAdmin)
            .map { event ->
                val eventName = event.event() ?: "message"
                val payload = event.data().toJsonNode()
                val wrapped = objectMapper.writeValueAsString(
                    V2ApiResponses.success(
                        V2SubmissionStreamEventData(
                            event = eventName,
                            payload = payload,
                        )
                    )
                )
                ServerSentEvent.builder<String>()
                    .event(eventName)
                    .data(wrapped)
                    .build()
            }
    }

    @GetMapping("/{submissionId}")
    @Operation(
        summary = "Get final v2 submission result",
        description = "Returns the aggregated verdict and per-test-case results wrapped in the A&I v2 envelope after judging completes. Returns 409 if the submission is unknown or still pending/running. In Swagger UI, submit first, then paste the returned `submissionId` here after the worker finishes.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Final result is available.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        examples = [
                            ExampleObject(
                                name = "accepted-result",
                                value = """{"success":true,"data":{"submissionId":"2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972","status":"ACCEPTED","testCases":[{"caseId":1,"status":"PASSED","timeMs":1.37,"memoryMb":12.4,"output":"비공개","error":null}]},"error":null,"timestamp":"2026-04-09T02:15:30.123Z"}""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "Submission not found or not finished yet.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        examples = [
                            ExampleObject(
                                name = "not-ready",
                                value = """{"success":false,"data":null,"error":{"code":"55031","message":"Submission result is not ready","value":"2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972","alert":"채점이 아직 완료되지 않았습니다."},"timestamp":"2026-04-09T02:15:30.123Z"}""",
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )
    suspend fun getResult(
        @Parameter(
            description = "Submission identifier returned by the create submission API.",
            example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
        )
        @PathVariable submissionId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<V2SubmissionResultData>> {
        val access = exchange.requestAccess()
        val result = submissionService.getResult(submissionId, access.submitterId, access.isAdmin)
            ?: return ResponseEntity.status(409).body(
                V2ApiResponses.error(
                    errorCode = V2ErrorCode.JUDGE_RESULT_NOT_READY,
                    message = "Submission result is not ready",
                    value = submissionId,
                    alert = "채점이 아직 완료되지 않았습니다.",
                )
            )
        return ResponseEntity.ok(V2ApiResponses.success(result.toV2()))
    }

    private fun String?.toJsonNode(): JsonNode =
        runCatching { objectMapper.readTree(this ?: "null") }
            .getOrElse { objectMapper.valueToTree(this) }
}
