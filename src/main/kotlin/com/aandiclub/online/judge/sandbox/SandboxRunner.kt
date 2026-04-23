package com.aandiclub.online.judge.sandbox

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.TestCaseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class SandboxInput(
    val code: String,
    val args: List<Any?>,
)

data class SandboxCaseInput(
    val caseId: Int,
    val args: List<Any?>,
)

data class SandboxOutput(
    val status: TestCaseStatus,
    val output: Any?,
    val error: String?,
    val timeMs: Double,
    val memoryMb: Double,
)

data class SandboxCaseOutput(
    val caseId: Int,
    val status: TestCaseStatus,
    val output: Any?,
    val error: String?,
    val timeMs: Double,
    val memoryMb: Double,
)

/** Internal DTO for deserializing the runner's stdout JSON. */
private data class RunnerResult(
    val status: String? = null,
    val output: Any?,
    val error: String?,
    val timeMs: Double = 0.0,
    val memoryMb: Double = 0.0,
)

@Component
class SandboxRunner(
    private val properties: SandboxProperties,
    private val objectMapper: ObjectMapper,
    @Suppress("unused")
    private val dockerStatsClient: DockerStatsClient,
) {
    private val log = LoggerFactory.getLogger(SandboxRunner::class.java)

    suspend fun run(language: Language, input: SandboxInput): SandboxOutput =
        runCase(
            language = language,
            code = input.code,
            testCase = SandboxCaseInput(caseId = 1, args = input.args),
        ).toSandboxOutput()

    suspend fun runCase(
        language: Language,
        code: String,
        testCase: SandboxCaseInput,
    ): SandboxCaseOutput =
        runAll(language, code, listOf(testCase))
            .firstOrNull()
            ?: SandboxCaseOutput(
                caseId = testCase.caseId,
                status = TestCaseStatus.RUNTIME_ERROR,
                output = null,
                error = "RUNTIME_ERROR: sandbox produced no case result",
                timeMs = 0.0,
                memoryMb = 0.0,
            )

    suspend fun runAll(
        language: Language,
        code: String,
        testCases: List<SandboxCaseInput>,
    ): List<SandboxCaseOutput> = withContext(Dispatchers.IO) {
        if (testCases.isEmpty()) {
            return@withContext emptyList()
        }

        val image = properties.images[language.value]
            ?: error("No sandbox image configured for language: $language")

        val payload = objectMapper.writeValueAsString(
            mapOf(
                "code" to code,
                "cases" to testCases,
            )
        )

        val rawOutput = runContainer(image, payload, language, testCases)
        parseRunnerBatchOutput(rawOutput, language, testCases)
    }

    private fun runContainer(
        image: String,
        inputJson: String,
        language: Language,
        testCases: List<SandboxCaseInput>,
    ): String {
        val containerName = "judge-${UUID.randomUUID().toString().take(12)}"
        val memoryLimitMb = when (language) {
            Language.KOTLIN -> properties.kotlinMemoryLimitMb
            else -> properties.memoryLimitMb
        }
        val cmd = listOf(
            "docker", "run", "--rm",
            "--name", containerName,
            "--network", "none",
            "--cpus", properties.cpuLimit,
            "--memory", "${memoryLimitMb}m",
            "--read-only",
            "--security-opt", "no-new-privileges",
            "--pids-limit", "${properties.pidsLimit}",
            "--tmpfs", tmpfsMountOptions(language),
        ) + kotlinCompileEnvArgs(language) + listOf(
            "-i", image,
        )

        log.debug("Starting sandbox container: name={}, image={}, cases={}", containerName, image, testCases.size)

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()

        val stdinThread = Thread {
            try {
                process.outputStream.use { it.write(inputJson.toByteArray()) }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }

        val stdoutBuilder = StringBuilder()
        val stdoutThread = Thread {
            try {
                stdoutBuilder.append(process.inputStream.bufferedReader().readText())
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }

        val stderrBuilder = StringBuilder()
        val stderrThread = Thread {
            try {
                stderrBuilder.append(process.errorStream.bufferedReader().readText())
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }

        process.waitFor()

        stdoutThread.join(500)
        stderrThread.join(500)
        stdinThread.join(200)

        val rawOutput = stdoutBuilder.toString().trim()
        val stderrOutput = stderrBuilder.toString().trim()
        val exitCode = process.exitValue()

        log.debug(
            "Container {} exited: code={}, output.length={}, stderr.length={}",
            containerName,
            exitCode,
            rawOutput.length,
            stderrOutput.length,
        )

        if (stderrOutput.isNotBlank()) {
            log.warn("Container {} stderr: {}", containerName, stderrOutput.take(4_000))
        }

        if (rawOutput.isBlank() && stderrOutput.isNotBlank()) {
            log.warn("Container {} produced blank stdout. stderr={}", containerName, stderrOutput)
        }

        return rawOutput
    }

    private fun kotlinCompileEnvArgs(language: Language): List<String> {
        if (language != Language.KOTLIN) {
            return emptyList()
        }

        return listOf(
            "-e", "KOTLIN_COMPILE_XMS_MB=${properties.kotlinCompileJvmMinMb}",
            "-e", "KOTLIN_COMPILE_XMX_MB=${properties.kotlinCompileJvmMaxMb}",
        )
    }

    private fun parseRunnerBatchOutput(
        rawOutput: String,
        language: Language,
        testCases: List<SandboxCaseInput>,
    ): List<SandboxCaseOutput> {
        if (rawOutput.isBlank()) {
            return testCases.map {
                SandboxCaseOutput(
                    caseId = it.caseId,
                    status = TestCaseStatus.RUNTIME_ERROR,
                    output = null,
                    error = "RUNTIME_ERROR: container produced no output",
                    timeMs = 0.0,
                    memoryMb = 0.0,
                )
            }
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            val root = objectMapper.readValue(rawOutput, Map::class.java) as Map<String, Any?>
            val rawResults = root["results"] as? List<*>
            if (rawResults.isNullOrEmpty()) {
                val fallback = parseRunnerOutput(rawOutput, exitCode = 0, language = language, externalMemoryMb = 0.0)
                return testCases.map {
                    SandboxCaseOutput(
                        caseId = it.caseId,
                        status = fallback.status,
                        output = fallback.output,
                        error = fallback.error,
                        timeMs = fallback.timeMs,
                        memoryMb = fallback.memoryMb,
                    )
                }
            }

            val parsedByCaseId = rawResults.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val caseId = (map["caseId"] as? Number)?.toInt() ?: return@mapNotNull null
                caseId to parseCaseOutput(caseId, map)
            }.toMap()

            testCases.map { testCase ->
                parsedByCaseId[testCase.caseId]
                    ?: SandboxCaseOutput(
                        caseId = testCase.caseId,
                        status = TestCaseStatus.RUNTIME_ERROR,
                        output = null,
                        error = "RUNTIME_ERROR: missing result for caseId=${testCase.caseId}",
                        timeMs = 0.0,
                        memoryMb = 0.0,
                    )
            }
        } catch (e: Exception) {
            log.error("Failed to parse runner batch output: {}", rawOutput, e)
            testCases.map {
                SandboxCaseOutput(
                    caseId = it.caseId,
                    status = TestCaseStatus.RUNTIME_ERROR,
                    output = null,
                    error = "RUNTIME_ERROR: failed to parse runner output: ${e.message}",
                    timeMs = 0.0,
                    memoryMb = 0.0,
                )
            }
        }
    }

    private fun parseCaseOutput(caseId: Int, map: Map<*, *>): SandboxCaseOutput {
        val error = map["error"] as? String
        val status = resolveStatus(map["status"] as? String, error)
        if (status == TestCaseStatus.COMPILE_ERROR) {
            if (error.isNullOrBlank() || error == "COMPILE_ERROR: ") {
                log.error("Compile error for caseId={} arrived without detail. payload={}", caseId, map)
            } else {
                log.warn("Compile error for caseId={}: {}", caseId, error)
            }
        }
        return SandboxCaseOutput(
            caseId = caseId,
            status = status,
            output = map["output"],
            error = error,
            timeMs = (map["timeMs"] as? Number)?.toDouble() ?: 0.0,
            memoryMb = (map["memoryMb"] as? Number)?.toDouble() ?: 0.0,
        )
    }

    private fun resolveStatus(status: String?, error: String?): TestCaseStatus = when {
        status != null -> runCatching { TestCaseStatus.valueOf(status) }.getOrElse { inferStatus(error) }
        else -> inferStatus(error)
    }

    private fun inferStatus(error: String?): TestCaseStatus = when {
        error != null && error.startsWith(TestCaseStatus.COMPILE_ERROR.name) -> TestCaseStatus.COMPILE_ERROR
        error != null && error.startsWith(TestCaseStatus.TIME_LIMIT_EXCEEDED.name) -> TestCaseStatus.TIME_LIMIT_EXCEEDED
        error != null && error.startsWith(TestCaseStatus.MEMORY_LIMIT_EXCEEDED.name) -> TestCaseStatus.MEMORY_LIMIT_EXCEEDED
        error != null -> TestCaseStatus.RUNTIME_ERROR
        else -> TestCaseStatus.PASSED
    }

    private fun parseRunnerOutput(
        rawOutput: String,
        exitCode: Int,
        language: Language,
        externalMemoryMb: Double,
    ): SandboxOutput {
        if (rawOutput.isBlank()) {
            return SandboxOutput(
                status = TestCaseStatus.RUNTIME_ERROR,
                output = null,
                error = "RUNTIME_ERROR: container produced no output (exit code $exitCode)",
                timeMs = 0.0,
                memoryMb = externalMemoryMb,
            )
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(rawOutput, Map::class.java) as Map<String, Any?>
            val result = RunnerResult(
                status = map["status"] as? String,
                output = map["output"],
                error = map["error"] as? String,
                timeMs = (map["timeMs"] as? Number)?.toDouble() ?: 0.0,
                memoryMb = (map["memoryMb"] as? Number)?.toDouble() ?: 0.0,
            )

            val resolvedMemoryMb = if (language == Language.PYTHON) result.memoryMb else externalMemoryMb.takeIf { it > 0.0 }
                ?: result.memoryMb

            SandboxOutput(
                status = resolveStatus(result.status, result.error),
                output = result.output,
                error = result.error,
                timeMs = result.timeMs,
                memoryMb = resolvedMemoryMb,
            )
        } catch (e: Exception) {
            log.error("Failed to parse runner output: {}", rawOutput, e)
            SandboxOutput(
                status = TestCaseStatus.RUNTIME_ERROR,
                output = null,
                error = "RUNTIME_ERROR: failed to parse runner output: ${e.message}",
                timeMs = 0.0,
                memoryMb = externalMemoryMb,
            )
        }
    }

    private fun tmpfsMountOptions(language: Language): String = when (language) {
        Language.DART -> "/tmp:rw,exec,nosuid,size=64m"
        Language.PYTHON, Language.KOTLIN -> "/tmp:rw,noexec,nosuid,size=64m"
    }

    private fun SandboxCaseOutput.toSandboxOutput(): SandboxOutput = SandboxOutput(
        status = status,
        output = output,
        error = error,
        timeMs = timeMs,
        memoryMb = memoryMb,
    )
}
