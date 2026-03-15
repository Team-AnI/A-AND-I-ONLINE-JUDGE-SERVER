package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.api.dto.SubmissionResult
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.service.SubmissionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
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

@RestController
@RequestMapping("/v1/submissions")
@SecurityRequirement(name = "bearerAuth")
@Tag(
    name = "Submissions",
    description = "Submit source code, follow live judge events, and retrieve final verdicts.",
)
class SubmissionController(
    private val submissionService: SubmissionService,
) {
    private val log = LoggerFactory.getLogger(SubmissionController::class.java)

    @PostMapping
    @Operation(
        summary = "Create a new submission",
        description = "Validates and stores a submission, starts asynchronous judging, and returns a submissionId with an SSE stream URL.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "202",
                description = "Submission accepted and queued for judging.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = SubmissionAccepted::class),
                        examples = [
                            ExampleObject(
                                name = "accepted",
                                value = """{"submissionId":"2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972","streamUrl":"/v1/submissions/2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972/stream"}""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "400", description = "Validation failed for the request payload."),
            ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT."),
            ApiResponse(responseCode = "429", description = "Submission rate limit exceeded."),
        ],
    )
    suspend fun submit(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Code submission payload. Use a problemId from the configured catalog and provide source code for one supported language.",
            required = true,
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = SubmissionRequest::class),
                    examples = [
                        ExampleObject(
                            name = "kotlin-sum",
                            value = """{"problemId":"quiz-101","language":"KOTLIN","code":"fun main() {\n    val (a, b) = readln().split(\" \").map { it.toInt() }\n    println(a + b)\n}","options":{"realtimeFeedback":true}}""",
                        ),
                    ],
                ),
            ],
        )
        @Valid @RequestBody request: SubmissionRequest,
    ): ResponseEntity<SubmissionAccepted> {
        val accepted = submissionService.createSubmission(request)
        SubmissionMdc.withSubmissionId(accepted.submissionId) {
            log.info(
                "Submission request accepted: problemId={}, language={}, streamUrl={}",
                request.problemId,
                request.language,
                accepted.streamUrl
            )
        }
        return ResponseEntity.accepted().body(accepted)
    }

    // SSE 스트림: 각 테스트 케이스 완료 시 즉시 이벤트 전송
    @GetMapping("/{submissionId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Stream live judge events",
        description = "Subscribes to a text/event-stream feed for a submission. The stream emits `test_case_result`, `done`, and `error` events in real time.",
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
                                value = "event: test_case_result\ndata: {\"caseId\":1,\"status\":\"PASSED\",\"timeMs\":1.37,\"memoryMb\":12.4}\n\nevent: done\ndata: {\"event\":\"done\",\"submissionId\":\"2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972\",\"overallStatus\":\"ACCEPTED\"}",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT."),
        ],
    )
    fun streamResults(
        @Parameter(
            description = "Submission identifier returned by the create submission API.",
            example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
        )
        @PathVariable submissionId: String,
    ): Flow<ServerSentEvent<String>> {
        SubmissionMdc.withSubmissionId(submissionId) {
            log.info("Submission stream subscribed")
        }
        return submissionService.streamResults(submissionId)
    }

    // 동기 결과 조회 (realtime_feedback: false 또는 완료 후 폴링)
    @GetMapping("/{submissionId}")
    @Operation(
        summary = "Get final submission result",
        description = "Returns the aggregated verdict and per-test-case results after judging completes. Returns 404 if the submission is unknown or still pending/running.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Final result is available.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = SubmissionResult::class),
                        examples = [
                            ExampleObject(
                                name = "accepted-result",
                                value = """{"submissionId":"2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972","status":"ACCEPTED","testCases":[{"caseId":1,"status":"PASSED","timeMs":1.37,"memoryMb":12.4,"output":"8","error":null}]}""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT."),
            ApiResponse(responseCode = "404", description = "Submission not found or not finished yet."),
        ],
    )
    suspend fun getResult(
        @Parameter(
            description = "Submission identifier returned by the create submission API.",
            example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
        )
        @PathVariable submissionId: String,
    ): ResponseEntity<SubmissionResult> {
        SubmissionMdc.withSubmissionId(submissionId) {
            log.debug("Submission result requested")
        }
        val result = submissionService.getResult(submissionId)
            ?: return ResponseEntity.notFound().build<SubmissionResult>().also {
                SubmissionMdc.withSubmissionId(submissionId) {
                    log.debug("Submission result not ready")
                }
            }
        SubmissionMdc.withSubmissionId(submissionId) {
            log.info("Submission result responded: status={}", result.status)
        }
        return ResponseEntity.ok(result)
    }
}
