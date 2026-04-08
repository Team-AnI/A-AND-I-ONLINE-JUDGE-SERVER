import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val KOTLIN_LIB_CLASSPATH = "/opt/kotlinc/lib/*"

fun main() {
    val raw = System.`in`.bufferedReader().readText()

    val payload = try {
        JSONObject(raw)
    } catch (e: Exception) {
        println(buildErrorJson("INTERNAL_ERROR: failed to parse input: ${e.message}", 0.0))
        return
    }

    val code = payload.optString("code", "")
    val argsJson = payload.optJSONArray("args") ?: JSONArray()
    val argsLiteral = buildArgsLiteral(argsJson)

    val tmpDir = File("/tmp/judge_${System.nanoTime()}").also { it.mkdirs() }
    val sourceFile = File(tmpDir, "Solution.kt")
    val resultFile = File(tmpDir, "result.txt")

    sourceFile.writeText(buildSolutionSource(code, argsLiteral, resultFile.absolutePath))

    val solutionJar = File(tmpDir, "solution.jar")

    // Invoke the compiler directly to avoid kotlinc's default 512M heap,
    // which is too large for the sandbox container memory limit.
    val compileProc = ProcessBuilder(
        "java",
        "-Xms32m",
        "-Xmx128m",
        "-cp", KOTLIN_LIB_CLASSPATH,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        sourceFile.absolutePath,
        "-d", solutionJar.absolutePath,
    ).redirectErrorStream(true).start()

    val compileOut = compileProc.inputStream.bufferedReader().readText()
    val compileExit = compileProc.waitFor()

    if (compileExit != 0) {
        tmpDir.deleteRecursively()
        val errMsg = compileOut.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        println(buildErrorJson("COMPILE_ERROR: $errMsg", 0.0))
        return
    }

    // Run the compiled program with the Kotlin runtime on the classpath.
    ProcessBuilder(
        "java",
        "-cp", "${solutionJar.absolutePath}:$KOTLIN_LIB_CLASSPATH",
        "SolutionKt",
    )
        .redirectErrorStream(true)
        .start()
        .waitFor()

    // Read result written by the compiled solution
    val resultPayload = if (resultFile.exists()) JSONObject(resultFile.readText()) else null
    tmpDir.deleteRecursively()

    val status = resultPayload?.optString("status") ?: "RUNTIME_ERROR"
    val timeMs = resultPayload?.optDouble("timeMs") ?: 0.0

    if (status == "OK") {
        val output = resultPayload?.opt("output")
        println(JSONObject()
            .put("output", if (output == null || output == JSONObject.NULL) JSONObject.NULL else output)
            .put("error", JSONObject.NULL)
            .put("timeMs", timeMs)
            .put("memoryMb", 0.0)
            .toString())
    } else {
        println(buildErrorJson("$status: ${resultPayload?.optString("error") ?: "no output"}", timeMs))
    }
}

fun buildArgsLiteral(argsJson: JSONArray): String =
    (0 until argsJson.length()).joinToString(", ") { i ->
        toLiteral(argsJson.get(i))
    }

fun toLiteral(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
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

/** Generates Solution.kt source: user code + main() that writes result to a file. */
fun buildSolutionSource(code: String, argsLiteral: String, resultFilePath: String): String {
    val safeResultPath = resultFilePath.replace("\\", "\\\\").replace("\"", "\\\"")
    return "import java.io.File\n\n" +
        "fun __judgeQuoteJson(value: String): String = buildString {\n" +
        "    append('\"')\n" +
        "    value.forEach { ch ->\n" +
        "        when (ch) {\n" +
        "            '\\\\' -> append(\"\\\\\\\\\")\n" +
        "            '\"' -> append(\"\\\\\\\"\")\n" +
        "            '\\n' -> append(\"\\\\n\")\n" +
        "            '\\r' -> append(\"\\\\r\")\n" +
        "            '\\t' -> append(\"\\\\t\")\n" +
        "            else -> append(ch)\n" +
        "        }\n" +
        "    }\n" +
        "    append('\"')\n" +
        "}\n\n" +
        "fun __judgeToJsonLiteral(value: Any?): String = when {\n" +
        "    value == null -> \"null\"\n" +
        "    value is String -> __judgeQuoteJson(value)\n" +
        "    value is Number || value is Boolean -> value.toString()\n" +
        "    value is Array<*> -> value.joinToString(prefix = \"[\", postfix = \"]\") { __judgeToJsonLiteral(it) }\n" +
        "    value is Iterable<*> -> value.joinToString(prefix = \"[\", postfix = \"]\") { __judgeToJsonLiteral(it) }\n" +
        "    value is Map<*, *> -> value.entries.joinToString(prefix = \"{\", postfix = \"}\") { entry -> __judgeQuoteJson(entry.key.toString()) + \":\" + __judgeToJsonLiteral(entry.value) }\n" +
        "    value.javaClass.isArray -> {\n" +
        "        val length = java.lang.reflect.Array.getLength(value)\n" +
        "        (0 until length).joinToString(prefix = \"[\", postfix = \"]\") { i -> __judgeToJsonLiteral(java.lang.reflect.Array.get(value, i)) }\n" +
        "    }\n" +
        "    else -> __judgeQuoteJson(value.toString())\n" +
        "}\n\n" +
        code + "\n\n" +
        "fun main() {\n" +
        "    val resultFile = File(\"$safeResultPath\")\n" +
        "    val t0 = System.nanoTime()\n" +
        "    try {\n" +
        "        val result = solution($argsLiteral)\n" +
        "        val ms = (System.nanoTime() - t0) / 1_000_000.0\n" +
        "        resultFile.writeText(\"{\\\"status\\\":\\\"OK\\\",\\\"output\\\":\" + __judgeToJsonLiteral(result) + \",\\\"timeMs\\\":\" + ms + \"}\")\n" +
        "    } catch (e: Exception) {\n" +
        "        val ms = (System.nanoTime() - t0) / 1_000_000.0\n" +
        "        resultFile.writeText(\"{\\\"status\\\":\\\"RUNTIME_ERROR\\\",\\\"error\\\":\" + __judgeQuoteJson(e.message ?: \"unknown error\") + \",\\\"timeMs\\\":\" + ms + \"}\")\n" +
        "    }\n" +
        "}\n"
}

fun buildErrorJson(error: String, timeMs: Double): String =
    JSONObject()
        .put("output", JSONObject.NULL)
        .put("error", error)
        .put("timeMs", timeMs)
        .put("memoryMb", 0.0)
        .toString()
