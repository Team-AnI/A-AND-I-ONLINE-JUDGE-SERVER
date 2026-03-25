package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.MyProblemSubmissionRecord
import com.aandiclub.online.judge.service.SubmissionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v1/problems")
@SecurityRequirement(name = "bearerAuth")
@Tag(
    name = "Problem Submissions",
    description = "Retrieve the authenticated user's submission history for a problem.",
)
class ProblemSubmissionController(
    private val submissionService: SubmissionService,
) {
    @GetMapping("/{problemId}/submissions/me")
    @Operation(
        summary = "List my submissions for a problem",
        description = "Returns the authenticated user's submission result history for the given problem in reverse chronological order. In Swagger UI, reuse the same Bearer token used for submission creation and call this with `quiz-101` to inspect your history.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Submission history was returned.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        array = ArraySchema(schema = io.swagger.v3.oas.annotations.media.Schema(implementation = MyProblemSubmissionRecord::class)),
                        examples = [
                            ExampleObject(
                                name = "my-history",
                                value = """[{"submissionId":"sub-1","problemId":"quiz-101","language":"KOTLIN","status":"ACCEPTED","testCases":[{"caseId":1,"status":"PASSED","timeMs":1.2,"memoryMb":8.0,"output":8,"error":null}],"createdAt":"2026-03-15T19:00:00+09:00","completedAt":"2026-03-15T19:00:02+09:00"}]""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT."),
        ],
    )
    suspend fun getMyProblemSubmissions(
        @Parameter(
            description = "Problem identifier registered in the judge catalog.",
            example = "quiz-101",
        )
        @PathVariable problemId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<List<MyProblemSubmissionRecord>> {
        val access = exchange.requestAccess()
        return ResponseEntity.ok(
            submissionService.getProblemSubmissions(
                problemId = problemId,
                submitterId = access.submitterId,
            )
        )
    }
}
