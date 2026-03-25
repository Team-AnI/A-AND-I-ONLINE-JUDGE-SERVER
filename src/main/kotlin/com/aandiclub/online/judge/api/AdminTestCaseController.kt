package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.ProblemTestCaseRecord
import com.aandiclub.online.judge.service.ProblemService
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
@RequestMapping("/v1/admin/testcases")
@SecurityRequirement(name = "bearerAuth")
@Tag(
    name = "Admin Test Cases",
    description = "Administrative access to all problem test cases.",
)
class AdminTestCaseController(
    private val problemService: ProblemService,
) {
    @GetMapping
    @Operation(
        summary = "List all test cases",
        description = "Returns test cases for all problems in the system. Requires ADMIN role. In Swagger UI, authorize with an ADMIN token and call directly.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "All test cases were returned.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        array = ArraySchema(schema = io.swagger.v3.oas.annotations.media.Schema(implementation = ProblemTestCaseRecord::class)),
                        examples = [
                            ExampleObject(
                                name = "testcase-list",
                                value = """[{"problemId":"quiz-101","testCases":[{"caseId":1,"args":[3,5],"argTypes":["INTEGER","INTEGER"],"expectedOutput":8,"expectedOutputType":"INTEGER"},{"caseId":2,"args":[10,20],"argTypes":["INTEGER","INTEGER"],"expectedOutput":30,"expectedOutputType":"INTEGER"}],"updatedAt":"2026-03-15T19:00:00+09:00"}]""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT."),
            ApiResponse(responseCode = "403", description = "Authenticated user is not an ADMIN."),
        ],
    )
    suspend fun getAllTestCases(): ResponseEntity<List<ProblemTestCaseRecord>> =
        ResponseEntity.ok(problemService.getAllProblemsWithTestCases())
}
