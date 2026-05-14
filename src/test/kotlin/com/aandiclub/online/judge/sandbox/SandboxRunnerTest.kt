package com.aandiclub.online.judge.sandbox

import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.TestCaseStatus
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class SandboxRunnerTest {

    private val properties = SandboxProperties(
        memoryLimitMb = 128,
        cpuLimit = "1.0",
        pidsLimit = 50,
        images = mapOf(
            "python" to "judge-sandbox-python:latest",
            "kotlin" to "judge-sandbox-kotlin:latest",
            "dart" to "judge-sandbox-dart:latest",
        ),
    )

    private val objectMapper = ObjectMapper()
    private val dockerStatsClient = mockk<DockerStatsClient>(relaxed = true)
    private val runner = SandboxRunner(properties, objectMapper, dockerStatsClient)

    @Test
    fun `sandbox properties are loaded correctly`() {
        assertEquals(128, properties.memoryLimitMb)
        assertEquals("1.0", properties.cpuLimit)
        assertEquals(50, properties.pidsLimit)
        assertEquals("judge-sandbox-python:latest", properties.images["python"])
        assertEquals("judge-sandbox-kotlin:latest", properties.images["kotlin"])
        assertEquals("judge-sandbox-dart:latest", properties.images["dart"])
    }

    @Test
    fun `run throws IllegalStateException when language image is not configured`() = runTest {
        val emptyRunner = SandboxRunner(
            SandboxProperties(images = emptyMap()),
            objectMapper,
            dockerStatsClient,
        )
        val input = SandboxInput(code = "def solution(): pass", args = emptyList())

        val ex = assertThrows<SandboxExecutionException> {
            emptyRunner.run(Language.PYTHON, input)
        }
        assert(ex.message!!.contains("PYTHON"))
        assertEquals(V2ErrorCode.JUDGE_WORKER_UNAVAILABLE, ex.errorCode)
    }

    @Test
    fun `SandboxInput stores code and args correctly`() {
        val input = SandboxInput(
            code = "def solution(x): return x * 2",
            args = listOf(42),
        )
        assertEquals("def solution(x): return x * 2", input.code)
        assertEquals(listOf(42), input.args)
    }

    @Test
    fun `SandboxOutput with TIME_LIMIT_EXCEEDED has correct status`() {
        val output = SandboxOutput(
            status = TestCaseStatus.TIME_LIMIT_EXCEEDED,
            output = null,
            error = "Exceeded 1s limit",
            timeMs = 1000.0,
            memoryMb = 0.0,
        )
        assertEquals(TestCaseStatus.TIME_LIMIT_EXCEEDED, output.status)
        assertNotNull(output.error)
    }

    @Test
    fun `SandboxOutput with COMPILE_ERROR has correct status`() {
        val output = SandboxOutput(
            status = TestCaseStatus.COMPILE_ERROR,
            output = null,
            error = "COMPILE_ERROR: syntax error",
            timeMs = 0.0,
            memoryMb = 0.0,
        )
        assertEquals(TestCaseStatus.COMPILE_ERROR, output.status)
        assertNotNull(output.error)
    }

    @Test
    fun `parseRunnerOutput preserves numeric output types`() {
        val result = parseRunnerOutput(
            """{"status":"PASSED","output":2,"error":null,"timeMs":1.2,"memoryMb":0.5}""",
            exitCode = 0,
            language = Language.PYTHON,
            externalMemoryMb = 0.0,
        )

        assertEquals(TestCaseStatus.PASSED, result.status)
        assertEquals(2, result.output)
        assertEquals(null, result.error)
    }

    @Test
    fun `parseRunnerOutput throws sandbox failure for invalid stdout`() {
        val ex = assertThrows<SandboxExecutionException> {
            parseRunnerOutput(
                "not-json",
                exitCode = 0,
                language = Language.PYTHON,
                externalMemoryMb = 0.0,
            )
        }

        assertEquals(V2ErrorCode.SANDBOX_EXECUTION_FAILED, ex.errorCode)
    }

    @Test
    fun `parseRunnerBatchOutput throws sandbox failure for blank stdout`() {
        val ex = assertThrows<SandboxExecutionException> {
            parseRunnerBatchOutput(
                rawOutput = "",
                language = Language.PYTHON,
                testCases = listOf(SandboxCaseInput(caseId = 1, args = listOf(1))),
            )
        }

        assertEquals(V2ErrorCode.SANDBOX_EXECUTION_FAILED, ex.errorCode)
    }

    @Test
    fun `parseRunnerBatchOutput throws sandbox failure for broken protocol`() {
        val ex = assertThrows<SandboxExecutionException> {
            parseRunnerBatchOutput(
                rawOutput = """{"ok":true}""",
                language = Language.PYTHON,
                testCases = listOf(SandboxCaseInput(caseId = 1, args = listOf(1))),
            )
        }

        assertEquals(V2ErrorCode.SANDBOX_EXECUTION_FAILED, ex.errorCode)
    }

    @Test
    fun `parseRunnerBatchOutput throws sandbox failure for unknown status without user error`() {
        val ex = assertThrows<SandboxExecutionException> {
            parseRunnerBatchOutput(
                rawOutput = """{"results":[{"caseId":1,"status":"BROKEN","output":null,"error":null}]}""",
                language = Language.PYTHON,
                testCases = listOf(SandboxCaseInput(caseId = 1, args = listOf(1))),
            )
        }

        assertEquals(V2ErrorCode.SANDBOX_EXECUTION_FAILED, ex.errorCode)
    }

    private fun parseRunnerOutput(
        rawOutput: String,
        exitCode: Int,
        language: Language,
        externalMemoryMb: Double,
    ): SandboxOutput {
        val method: Method = SandboxRunner::class.java.getDeclaredMethod(
            "parseRunnerOutput",
            String::class.java,
            Int::class.javaPrimitiveType,
            Language::class.java,
            Double::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return invokePrivate(method, runner, rawOutput, exitCode, language, externalMemoryMb) as SandboxOutput
    }

    private fun parseRunnerBatchOutput(
        rawOutput: String,
        language: Language,
        testCases: List<SandboxCaseInput>,
    ): List<SandboxCaseOutput> {
        val method: Method = SandboxRunner::class.java.getDeclaredMethod(
            "parseRunnerBatchOutput",
            String::class.java,
            Language::class.java,
            List::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return invokePrivate(method, runner, rawOutput, language, testCases) as List<SandboxCaseOutput>
    }

    private fun invokePrivate(method: Method, target: Any, vararg args: Any?): Any? {
        try {
            return method.invoke(target, *args)
        } catch (ex: InvocationTargetException) {
            throw ex.targetException
        }
    }
}
