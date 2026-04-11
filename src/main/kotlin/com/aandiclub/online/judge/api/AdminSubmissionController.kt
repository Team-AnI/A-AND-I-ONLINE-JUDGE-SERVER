package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.service.SubmissionService
import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/admin/submissions")
@SecurityRequirement(name = "bearerAuth")
@Tag(
    name = "Admin Submissions",
    description = "Administrative access to all stored submission records.",
)
class AdminSubmissionController(
    private val submissionService: SubmissionService,
) {
    @GetMapping
    @Operation(
        summary = "List all submissions",
        description = "Returns every stored submission record in reverse chronological order. Requires ADMIN role. In Swagger UI, authorize with an ADMIN token and call directly without query parameters.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "All submission records were returned.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        array = ArraySchema(schema = io.swagger.v3.oas.annotations.media.Schema(implementation = AdminSubmissionRecord::class)),
                        examples = [
                            ExampleObject(
                                name = "admin-list",
                                value = """[{"submissionId":"sub-1","submitterId":"95d7a3b1-2c22-4a32-b343-1d4efdfde001","submitterPublicCode":"A00123","problemId":"quiz-101","language":"PYTHON","code":"def solution(a, b):\n    return a + b","status":"ACCEPTED","testCases":[{"caseId":1,"status":"PASSED","timeMs":1.2,"memoryMb":2.4,"output":"비공개","error":null}],"createdAt":"2026-03-15T19:00:00+09:00","completedAt":"2026-03-15T19:00:02+09:00"}]""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT."),
            ApiResponse(responseCode = "403", description = "Authenticated user is not an ADMIN."),
        ],
    )
    suspend fun getAllSubmissions(): ResponseEntity<List<AdminSubmissionRecord>> =
        ResponseEntity.ok(submissionService.getAllSubmissions())
}
