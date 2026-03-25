package com.aandiclub.online.judge.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@Schema(description = "Response containing test cases for a specific problem")
data class ProblemTestCaseRecord(
    @Schema(description = "Problem identifier", example = "quiz-101")
    val problemId: String,

    @Schema(description = "List of test cases for this problem, including how the judge currently interprets each value type.")
    val testCases: List<AdminTestCaseRecord>,

    @Schema(description = "Last update timestamp for this problem's test cases", example = "2026-03-15T19:00:00+09:00")
    val updatedAt: OffsetDateTime,
)

@Schema(description = "Administrative test case view with inferred runtime value types.")
data class AdminTestCaseRecord(
    @field:Schema(description = "Sequential case identifier within the problem.", example = "1")
    val caseId: Int,
    @field:Schema(description = "Raw argument values that will be passed into solution(...).")
    val args: List<Any?>,
    @field:Schema(description = "Type inferred for each argument value, in order.", example = "[\"INTEGER\",\"INTEGER\"]")
    val argTypes: List<String>,
    @field:Schema(description = "Expected output value used by the judge.")
    val expectedOutput: Any?,
    @field:Schema(description = "Type inferred for expectedOutput.", example = "INTEGER")
    val expectedOutputType: String,
)
