import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val KOTLIN_LIB_CLASSPATH = "/opt/kotlinc/lib/*"
private const val RUNNER_CLASSPATH = "/app/lib/org.json.jar:$KOTLIN_LIB_CLASSPATH"
private const val GENERATED_SOURCE_CLASSPATH = "/app/lib/org.json.jar"
private val KOTLIN_IMPORT_DIRECTIVE = Regex("""^\s*import\s+.+$""")

data class PreparedKotlinSource(
    val imports: List<String>,
    val body: String,
    val error: String? = null,
)

fun main() {
    val raw = System.`in`.bufferedReader().readText()

    val payload = try {
        JSONObject(raw)
    } catch (e: Exception) {
        println(buildSingleErrorJson("INTERNAL_ERROR: failed to parse input: ${e.message}", 0.0))
        return
    }

    val code = payload.optString("code", "")
    val isBatch = payload.has("cases")
    val casesJson = if (isBatch) {
        payload.optJSONArray("cases") ?: JSONArray()
    } else {
        JSONArray().put(
            JSONObject()
                .put("caseId", 1)
                .put("args", payload.optJSONArray("args") ?: JSONArray())
        )
    }

    val result = executeBatch(code, casesJson)
    if (isBatch) {
        println(result.toString())
        return
    }

    val first = result.optJSONArray("results")?.optJSONObject(0)
    if (first == null) {
        println(buildSingleErrorJson("RUNTIME_ERROR: runner produced no single result", 0.0))
        return
    }
    first.remove("caseId")
    println(first.toString())
}

fun executeBatch(code: String, casesJson: JSONArray): JSONObject {
    val preparedSource = prepareKotlinSource(code)
    if (preparedSource.error != null) {
        return buildBatchErrorJson(casesJson, "COMPILE_ERROR: ${preparedSource.error}")
    }

    val tmpDir = File("/tmp/judge_${System.nanoTime()}").also { it.mkdirs() }
    return try {
        val sourceFile = File(tmpDir, "Solution.kt")
        val solutionJar = File(tmpDir, "solution.jar")
        sourceFile.writeText(buildSolutionSource(preparedSource, casesJson))

        val compileProc = ProcessBuilder(
            "java",
            "-Xms32m",
            "-Xmx128m",
            "-cp", RUNNER_CLASSPATH,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-classpath", GENERATED_SOURCE_CLASSPATH,
            sourceFile.absolutePath,
            "-d", solutionJar.absolutePath,
        ).redirectErrorStream(true).start()

        val compileOut = compileProc.inputStream.bufferedReader().readText()
        val compileExit = compileProc.waitFor()
        if (compileExit != 0) {
            return buildBatchErrorJson(casesJson, "COMPILE_ERROR: ${compileOut.sanitizeForJson()}")
        }

        val runProc = ProcessBuilder(
            "java",
            "-cp", "${solutionJar.absolutePath}:$RUNNER_CLASSPATH",
            "SolutionKt",
        ).redirectErrorStream(true).start()

        val runOutput = runProc.inputStream.bufferedReader().readText().trim()
        val runExit = runProc.waitFor()
        if (runOutput.isBlank()) {
            return buildBatchErrorJson(
                casesJson,
                "RUNTIME_ERROR: compiled program produced no output (exit code $runExit)",
            )
        }

        return try {
            JSONObject(runOutput)
        } catch (e: Exception) {
            buildBatchErrorJson(casesJson, "RUNTIME_ERROR: failed to parse runner output: ${e.message}")
        }
    } finally {
        tmpDir.deleteRecursively()
    }
}

fun buildArgsLiteral(argsJson: JSONArray): String =
    (0 until argsJson.length()).joinToString(", ") { i ->
        toLiteral(argsJson.get(i))
    }

fun toLiteral(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"") }\""
    is Number, is Boolean -> value.toString()
    is JSONArray -> {
        val elements = (0 until value.length()).joinToString(", ") { toLiteral(value.get(it)) }
        "listOf($elements)"
    }
    is JSONObject -> {
        val entries = value.keys().asSequence().joinToString(", ") { key ->
            val k = "\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            val v = toLiteral(value.get(key))
            "$k to $v"
        }
        "mapOf($entries)"
    }
    else -> value.toString()
}

fun prepareKotlinSource(code: String): PreparedKotlinSource {
    val imports = mutableListOf<String>()
    val body = mutableListOf<String>()
    var inDirectiveSection = true
    var inBlockComment = false

    for (line in code.lineSequence()) {
        val trimmed = line.trim()

        if (inDirectiveSection) {
            if (inBlockComment) {
                body += line
                if ("*/" in trimmed) {
                    inBlockComment = false
                }
                continue
            }

            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                body += line
                continue
            }

            if (trimmed.startsWith("/*")) {
                body += line
                if (!trimmed.contains("*/")) {
                    inBlockComment = true
                }
                continue
            }

            if (trimmed.startsWith("package ")) {
                return PreparedKotlinSource(
                    imports = imports,
                    body = code,
                    error = "package declarations are not supported",
                )
            }

            if (KOTLIN_IMPORT_DIRECTIVE.matches(line)) {
                imports += trimmed
                continue
            }

            inDirectiveSection = false
        }

        body += line
    }

    return PreparedKotlinSource(imports = imports, body = body.joinToString("\n"))
}

fun buildSolutionSource(preparedSource: PreparedKotlinSource, casesJson: JSONArray): String {
    val importLines = buildList {
        preparedSource.imports.forEach { importLine ->
            if (importLine !in setOf(
                    "import java.util.concurrent.atomic.AtomicLong",
                    "import kotlin.math.max",
                    "import org.json.JSONArray",
                    "import org.json.JSONObject",
                )
            ) {
                add(importLine)
            }
        }
        add("import java.util.concurrent.atomic.AtomicLong")
        add("import kotlin.math.max")
        add("import org.json.JSONArray")
        add("import org.json.JSONObject")
    }.joinToString("\n")

    val caseBlocks = buildString {
        for (index in 0 until casesJson.length()) {
            val caseItem = casesJson.getJSONObject(index)
            val caseId = caseItem.getInt("caseId")
            val argsLiteral = buildArgsLiteral(caseItem.optJSONArray("args") ?: JSONArray())
            appendLine("    run {")
            appendLine("        val runtime = Runtime.getRuntime()")
            appendLine("        val peakBytes = AtomicLong(max(0L, runtime.totalMemory() - runtime.freeMemory()))")
            appendLine("        var sampling = true")
            appendLine("        val sampler = Thread {")
            appendLine("            while (sampling) {")
            appendLine("                val used = max(0L, runtime.totalMemory() - runtime.freeMemory())")
            appendLine("                var current = peakBytes.get()")
            appendLine("                while (used > current && !peakBytes.compareAndSet(current, used)) {")
            appendLine("                    current = peakBytes.get()")
            appendLine("                }")
            appendLine("                try {")
            appendLine("                    Thread.sleep(1)")
            appendLine("                } catch (_: InterruptedException) {")
            appendLine("                    break")
            appendLine("                }")
            appendLine("            }")
            appendLine("        }.also { it.isDaemon = true; it.start() }")
            appendLine("        val t0 = System.nanoTime()")
            appendLine("        try {")
            appendLine("            val result = solution($argsLiteral)")
            appendLine("            val ms = (System.nanoTime() - t0) / 1_000_000.0")
            appendLine("            sampling = false")
            appendLine("            sampler.interrupt()")
            appendLine("            sampler.join(50)")
            appendLine("            results.put(__judgeRecord($caseId, \"PASSED\", result, null, ms, peakBytes.get() / (1024.0 * 1024.0)))")
            appendLine("        } catch (e: Throwable) {")
            appendLine("            val ms = (System.nanoTime() - t0) / 1_000_000.0")
            appendLine("            sampling = false")
            appendLine("            sampler.interrupt()")
            appendLine("            sampler.join(50)")
            appendLine("            val root = e.cause ?: e")
            appendLine("            results.put(__judgeRecord($caseId, \"RUNTIME_ERROR\", null, \"RUNTIME_ERROR: ${'$'}{root.message ?: root::class.simpleName ?: \"unknown error\"}\", ms, peakBytes.get() / (1024.0 * 1024.0)))")
            appendLine("        }")
            appendLine("    }")
        }
    }

    return importLines + "\n\n" +
        "fun __judgeJsonCompatible(value: Any?): Any? = when {\n" +
        "    value == null -> JSONObject.NULL\n" +
        "    value is String || value is Number || value is Boolean -> value\n" +
        "    value is Array<*> -> JSONArray(value.map { __judgeJsonCompatible(it) })\n" +
        "    value is IntArray -> JSONArray(value.toList())\n" +
        "    value is LongArray -> JSONArray(value.toList())\n" +
        "    value is DoubleArray -> JSONArray(value.toList())\n" +
        "    value is FloatArray -> JSONArray(value.map { it.toDouble() })\n" +
        "    value is BooleanArray -> JSONArray(value.toList())\n" +
        "    value is Iterable<*> -> JSONArray(value.map { __judgeJsonCompatible(it) })\n" +
        "    value is Map<*, *> -> JSONObject(value.entries.associate { it.key.toString() to __judgeJsonCompatible(it.value) })\n" +
        "    value.javaClass.isArray -> {\n" +
        "        val length = java.lang.reflect.Array.getLength(value)\n" +
        "        JSONArray((0 until length).map { idx -> __judgeJsonCompatible(java.lang.reflect.Array.get(value, idx)) })\n" +
        "    }\n" +
        "    else -> value.toString()\n" +
        "}\n\n" +
        "fun __judgeRecord(caseId: Int, status: String, output: Any?, error: String?, timeMs: Double, memoryMb: Double): JSONObject =\n" +
        "    JSONObject()\n" +
        "        .put(\"caseId\", caseId)\n" +
        "        .put(\"status\", status)\n" +
        "        .put(\"output\", __judgeJsonCompatible(output))\n" +
        "        .put(\"error\", error ?: JSONObject.NULL)\n" +
        "        .put(\"timeMs\", timeMs)\n" +
        "        .put(\"memoryMb\", memoryMb)\n\n" +
        preparedSource.body + "\n\n" +
        "fun main() {\n" +
        "    val results = JSONArray()\n" +
        caseBlocks +
        "    println(JSONObject().put(\"results\", results).toString())\n" +
        "}\n"
}

fun buildBatchErrorJson(casesJson: JSONArray, error: String): JSONObject {
    val results = JSONArray()
    for (index in 0 until casesJson.length()) {
        val caseItem = casesJson.getJSONObject(index)
        results.put(
            JSONObject()
                .put("caseId", caseItem.getInt("caseId"))
                .put("status", if (error.startsWith("COMPILE_ERROR")) "COMPILE_ERROR" else "RUNTIME_ERROR")
                .put("output", JSONObject.NULL)
                .put("error", error)
                .put("timeMs", 0.0)
                .put("memoryMb", 0.0)
        )
    }
    return JSONObject().put("results", results)
}

fun buildSingleErrorJson(error: String, timeMs: Double): String =
    JSONObject()
        .put("status", if (error.startsWith("COMPILE_ERROR")) "COMPILE_ERROR" else "RUNTIME_ERROR")
        .put("output", JSONObject.NULL)
        .put("error", error)
        .put("timeMs", timeMs)
        .put("memoryMb", 0.0)
        .toString()

fun String.sanitizeForJson(): String = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
