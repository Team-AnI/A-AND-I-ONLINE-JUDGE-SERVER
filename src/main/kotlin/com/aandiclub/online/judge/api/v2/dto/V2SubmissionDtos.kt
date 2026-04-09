package com.aandiclub.online.judge.api.v2.dto

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.api.dto.MyProblemSubmissionRecord
import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.api.dto.SubmissionOptions
import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.api.dto.SubmissionResult
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseResult
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.NullNode
import java.time.OffsetDateTime

@Schema(name = "V2SubmissionRequest", description = "A&I v2 request payload for creating a new submission.")
data class V2SubmissionRequest(
    @field:NotBlank
    @field:Size(max = 64)
    @field:Schema(description = "Public code shown to administrators for identifying the submitter.", example = "A00123")
    val publicCode: String,
    @field:NotBlank
    @field:Pattern(regexp = "^[a-zA-Z0-9-]+$")
    @field:Schema(description = "Problem identifier registered in the judge catalog.", example = "quiz-101")
    val problemId: String,
    @field:NotNull
    @field:Schema(description = "Programming language used for the submission.", example = "KOTLIN")
    val language: Language,
    @field:NotBlank
    @field:Size(max = 65_536)
    @field:Schema(description = "Source code to compile or execute in the sandbox.")
    val code: String,
    @field:Schema(description = "Execution and response options for this submission.")
    val options: V2SubmissionOptions = V2SubmissionOptions(),
)

@Schema(name = "V2SubmissionOptions", description = "Optional flags that change how the client consumes judge results.")
data class V2SubmissionOptions(
    @field:Schema(description = "When true, the client is expected to consume the SSE stream for per-test-case updates.", example = "true")
    val realtimeFeedback: Boolean = true,
)

@Schema(name = "V2SubmissionAcceptedData", description = "Accepted submission payload wrapped by the v2 envelope.")
data class V2SubmissionAcceptedData(
    @field:Schema(description = "Server-generated submission identifier.", example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972")
    val submissionId: String,
    @field:Schema(description = "Relative SSE endpoint that streams live judge events for this submission.", example = "/v2/submissions/2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972/stream")
    val streamUrl: String,
)

@Schema(name = "V2SubmissionResultData", description = "Final aggregated result after the judge completes.")
data class V2SubmissionResultData(
    @field:Schema(description = "Submission identifier.", example = "2af20dd4-04a5-4a6c-b3fa-6d9a9e5f9972")
    val submissionId: String,
    @field:Schema(description = "Overall verdict across all configured test cases.", example = "ACCEPTED")
    val status: SubmissionStatus,
    @field:ArraySchema(arraySchema = Schema(description = "Per-test-case execution results in evaluation order."))
    val testCases: List<TestCaseResult>,
)

@Schema(name = "V2MyProblemSubmissionRecord", description = "Submission history entry for the authenticated user on a single problem.")
data class V2MyProblemSubmissionRecord(
    val submissionId: String,
    val problemId: String,
    val language: Language,
    val status: SubmissionStatus,
    val testCases: List<TestCaseResult>,
    val createdAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
)

@Schema(name = "V2AdminSubmissionRecord", description = "Administrative submission record wrapped by the v2 envelope.")
data class V2AdminSubmissionRecord(
    val submissionId: String,
    val submitterId: String,
    val submitterPublicCode: String,
    val problemId: String,
    val language: Language,
    val code: String,
    val status: SubmissionStatus,
    val testCases: List<TestCaseResult>,
    val createdAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
)

@Schema(name = "V2SubmissionStreamEventData", description = "Envelope payload emitted for each v2 SSE event.")
data class V2SubmissionStreamEventData(
    @field:Schema(description = "SSE event name.", example = "test_case_result")
    val event: String,
    @field:Schema(description = "Original event payload, converted to a JSON node when possible.")
    val payload: JsonNode = NullNode.instance,
)

fun V2SubmissionRequest.toV1(): SubmissionRequest =
    SubmissionRequest(
        publicCode = publicCode,
        problemId = problemId,
        language = language,
        code = code,
        options = SubmissionOptions(realtimeFeedback = options.realtimeFeedback),
    )

fun SubmissionAccepted.toV2(streamUrl: String = "/v2/submissions/$submissionId/stream"): V2SubmissionAcceptedData =
    V2SubmissionAcceptedData(
        submissionId = submissionId,
        streamUrl = streamUrl,
    )

fun SubmissionResult.toV2(): V2SubmissionResultData =
    V2SubmissionResultData(
        submissionId = submissionId,
        status = status,
        testCases = testCases,
    )

fun MyProblemSubmissionRecord.toV2(): V2MyProblemSubmissionRecord =
    V2MyProblemSubmissionRecord(
        submissionId = submissionId,
        problemId = problemId,
        language = language,
        status = status,
        testCases = testCases,
        createdAt = createdAt,
        completedAt = completedAt,
    )

fun AdminSubmissionRecord.toV2(): V2AdminSubmissionRecord =
    V2AdminSubmissionRecord(
        submissionId = submissionId,
        submitterId = submitterId,
        submitterPublicCode = submitterPublicCode,
        problemId = problemId,
        language = language,
        code = code,
        status = status,
        testCases = testCases,
        createdAt = createdAt,
        completedAt = completedAt,
    )
