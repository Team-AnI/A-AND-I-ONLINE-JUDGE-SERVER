package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.AdminTestCaseRecord
import com.aandiclub.online.judge.api.dto.ProblemTestCaseRecord
import com.aandiclub.online.judge.repository.ProblemRepository
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
class ProblemService(
    private val problemRepository: ProblemRepository,
) {
    suspend fun getAllProblemsWithTestCases(): List<ProblemTestCaseRecord> =
        problemRepository.findAll()
            .collectList()
            .awaitSingle()
            .map { problem ->
                ProblemTestCaseRecord(
                    problemId = problem.problemId,
                    testCases = problem.testCases.map { testCase ->
                        AdminTestCaseRecord(
                            caseId = testCase.caseId,
                            args = testCase.args,
                            argTypes = testCase.args.map { inferValueType(it) },
                            expectedOutput = testCase.expectedOutput,
                            expectedOutputType = inferValueType(testCase.expectedOutput),
                        )
                    },
                    updatedAt = problem.updatedAt.toKstOffsetDateTime(),
                )
            }

    private fun Instant.toKstOffsetDateTime(): OffsetDateTime =
        atZone(KST_ZONE_ID).toOffsetDateTime()

    companion object {
        private val KST_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")
    }

    private fun inferValueType(value: Any?): String = when (value) {
        null -> "NULL"
        is Byte, is Short, is Int, is Long, is BigInteger -> "INTEGER"
        is Float, is Double, is BigDecimal -> "DECIMAL"
        is String -> "STRING"
        is Boolean -> "BOOLEAN"
        is List<*> -> "ARRAY"
        is Map<*, *> -> "OBJECT"
        else -> value::class.simpleName?.uppercase() ?: "UNKNOWN"
    }
}
