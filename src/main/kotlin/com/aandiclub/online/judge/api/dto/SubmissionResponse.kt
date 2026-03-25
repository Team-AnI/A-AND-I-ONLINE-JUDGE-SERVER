package com.aandiclub.online.judge.api.dto

import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseResult
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

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

@Schema(
    name = "MyProblemSubmissionRecord",
    description = "Submission history entry for the authenticated user on a single problem.",
)
data class MyProblemSubmissionRecord(
    @field:Schema(
        description = "Submission identifier.",
        example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
    )
    val submissionId: String,
    @field:Schema(
        description = "Problem identifier that was submitted.",
        example = "quiz-101",
    )
    val problemId: String,
    @field:Schema(
        description = "Programming language used for the submission.",
        example = "KOTLIN",
    )
    val language: Language,
    @field:Schema(
        description = "Current overall verdict for the submission.",
        example = "ACCEPTED",
    )
    val status: SubmissionStatus,
    @field:ArraySchema(
        arraySchema = Schema(description = "Per-test-case execution results in evaluation order."),
    )
    val testCases: List<TestCaseResult>,
    @field:Schema(
        description = "When the submission was created.",
        example = "2026-03-15T12:21:00+09:00",
    )
    val createdAt: OffsetDateTime,
    @field:Schema(
        description = "When judging completed, if it has completed.",
        example = "2026-03-15T12:21:02+09:00",
        nullable = true,
    )
    val completedAt: OffsetDateTime?,
)

@Schema(
    name = "AdminSubmissionRecord",
    description = "Full submission record returned to ADMIN users.",
)
data class AdminSubmissionRecord(
    @field:Schema(
        description = "Submission identifier.",
        example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972",
    )
    val submissionId: String,
    @field:Schema(
        description = "Internal authenticated submitter identifier from the JWT subject.",
        example = "95d7a3b1-2c22-4a32-b343-1d4efdfde001",
    )
    val submitterId: String,
    @field:Schema(
        description = "Public code submitted for administrator-facing identification.",
        example = "A00123",
    )
    val submitterPublicCode: String,
    @field:Schema(
        description = "Problem identifier that was submitted.",
        example = "quiz-101",
    )
    val problemId: String,
    @field:Schema(
        description = "Programming language used for the submission.",
        example = "KOTLIN",
    )
    val language: Language,
    @field:Schema(
        description = "Submitted source code.",
    )
    val code: String,
    @field:Schema(
        description = "Current overall verdict for the submission.",
        example = "ACCEPTED",
    )
    val status: SubmissionStatus,
    @field:ArraySchema(
        arraySchema = Schema(description = "Per-test-case execution results in evaluation order."),
    )
    val testCases: List<TestCaseResult>,
    @field:Schema(
        description = "When the submission was created.",
        example = "2026-03-15T12:21:00+09:00",
    )
    val createdAt: OffsetDateTime,
    @field:Schema(
        description = "When judging completed, if it has completed.",
        example = "2026-03-15T12:21:02+09:00",
        nullable = true,
    )
    val completedAt: OffsetDateTime?,
)
