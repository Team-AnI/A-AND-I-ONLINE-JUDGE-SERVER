package com.aandiclub.online.judge.api.v2.dto

import com.aandiclub.online.judge.api.dto.AdminTestCaseRecord
import com.aandiclub.online.judge.api.dto.ProblemTestCaseRecord
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@Schema(description = "Administrative test case view with inferred runtime value types for v2.")
data class V2AdminTestCaseRecord(
    val caseId: Int,
    val args: List<Any?>,
    val argTypes: List<String>,
    val expectedOutput: Any?,
    val expectedOutputType: String,
)

@Schema(description = "Response containing test cases for a specific problem in v2.")
data class V2ProblemTestCaseRecord(
    val problemId: String,
    val testCases: List<V2AdminTestCaseRecord>,
    val updatedAt: OffsetDateTime,
)

fun ProblemTestCaseRecord.toV2(): V2ProblemTestCaseRecord =
    V2ProblemTestCaseRecord(
        problemId = problemId,
        testCases = testCases.map { it.toV2() },
        updatedAt = updatedAt,
    )

fun AdminTestCaseRecord.toV2(): V2AdminTestCaseRecord =
    V2AdminTestCaseRecord(
        caseId = caseId,
        args = args,
        argTypes = argTypes,
        expectedOutput = expectedOutput,
        expectedOutputType = expectedOutputType,
    )
