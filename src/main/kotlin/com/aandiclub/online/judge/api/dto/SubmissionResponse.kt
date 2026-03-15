package com.aandiclub.online.judge.api.dto

import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseResult
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    name = "SubmissionAccepted",
    description = "Immediate response returned when a submission job has been accepted.",
)
data class SubmissionAccepted(
    @field:Schema(
        description = "Server-generated submission identifier.",
        example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
    )
    val submissionId: String,
    @field:Schema(
        description = "Relative SSE endpoint that streams live judge events for this submission.",
        example = "/v1/submissions/2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972/stream",
    )
    val streamUrl: String,
)

@Schema(
    name = "SubmissionResult",
    description = "Aggregated final result after the judge completes.",
)
data class SubmissionResult(
    @field:Schema(
        description = "Submission identifier.",
        example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
    )
    val submissionId: String,
    @field:Schema(
        description = "Overall verdict across all configured test cases.",
        example = "ACCEPTED",
    )
    val status: SubmissionStatus,
    @field:ArraySchema(
        arraySchema = Schema(description = "Per-test-case execution results in evaluation order."),
    )
    val testCases: List<TestCaseResult>,
)
