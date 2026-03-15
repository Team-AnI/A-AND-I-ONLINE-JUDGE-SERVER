package com.aandiclub.online.judge.domain

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    name = "TestCaseResult",
    description = "Outcome of a single test case execution.",
)
data class TestCaseResult(
    @field:Schema(description = "Sequential case identifier within the problem.", example = "1")
    val caseId: Int,
    @field:Schema(description = "Verdict for this test case.", example = "PASSED")
    val status: TestCaseStatus,
    @field:Schema(description = "Execution time in milliseconds.", example = "1.37")
    val timeMs: Double = 0.0,
    @field:Schema(description = "Peak memory usage in megabytes.", example = "12.4")
    val memoryMb: Double = 0.0,
    @field:Schema(description = "Captured program stdout, when available.", example = "8")
    val output: String? = null,
    @field:Schema(description = "Compiler or runtime error text, when available.", example = "")
    val error: String? = null,
)
