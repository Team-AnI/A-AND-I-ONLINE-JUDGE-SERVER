package com.aandiclub.online.judge.api.dto

import com.aandiclub.online.judge.domain.Language
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(
    name = "SubmissionRequest",
    description = "Request payload for creating a new code submission.",
)
data class SubmissionRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[a-zA-Z0-9-]+$")
    @field:Schema(
        description = "Problem identifier registered in the judge catalog.",
        example = "quiz-101",
    )
    val problemId: String,
    @field:NotNull
    @field:Schema(
        description = "Programming language used for the submission.",
        example = "KOTLIN",
    )
    val language: Language,
    @field:NotBlank
    @field:Size(max = 65_536)
    @field:Schema(
        description = "Source code to compile or execute in the sandbox.",
        example = "fun main() {\n    val (a, b) = readln().split(\" \").map { it.toInt() }\n    println(a + b)\n}",
    )
    val code: String,
    @field:Schema(description = "Execution and response options for this submission.")
    val options: SubmissionOptions = SubmissionOptions(),
)

@Schema(
    name = "SubmissionOptions",
    description = "Optional flags that change how the client consumes judge results.",
)
data class SubmissionOptions(
    @field:Schema(
        description = "When true, the client is expected to consume the SSE stream for per-test-case updates.",
        example = "true",
    )
    val realtimeFeedback: Boolean = true,
)
