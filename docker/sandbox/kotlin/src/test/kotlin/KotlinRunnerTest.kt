import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

// ── 반환값 직렬화 헬퍼 (buildSolutionSource에 삽입되는 __judgeToJsonLiteral 로직과 동일) ──
fun judgeToJsonLiteral(value: Any?): String {
    fun quoteJson(s: String) = buildString {
        append('"')
        s.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"'  -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
    return when (value) {
        null              -> "null"
        is String         -> quoteJson(value)
        is Number, is Boolean -> value.toString()
        is Array<*>       -> value.joinToString(prefix = "[", postfix = "]") { judgeToJsonLiteral(it) }
        is Iterable<*>    -> value.joinToString(prefix = "[", postfix = "]") { judgeToJsonLiteral(it) }
        is Map<*, *>      -> value.entries.joinToString(prefix = "{", postfix = "}") { entry ->
            quoteJson(entry.key.toString()) + ":" + judgeToJsonLiteral(entry.value)
        }
        else -> if (value.javaClass.isArray) {
            val length = java.lang.reflect.Array.getLength(value)
            (0 until length).joinToString(prefix = "[", postfix = "]") { i ->
                judgeToJsonLiteral(java.lang.reflect.Array.get(value, i))
            }
        } else {
            quoteJson(value.toString())
        }
    }
}

class KotlinRunnerTest {

    // ── buildArgsLiteral: 스칼라 타입 ─────────────────────────────────────

    @Test fun `buildArgsLiteral handles scalar int args`() {
        assertEquals("3, 5", buildArgsLiteral(JSONArray().put(3).put(5)))
    }

    @Test fun `buildArgsLiteral handles scalar float arg`() {
        assertEquals("1.5", buildArgsLiteral(JSONArray().put(1.5)))
    }

    @Test fun `buildArgsLiteral handles scalar boolean arg`() {
        assertEquals("true", buildArgsLiteral(JSONArray().put(true)))
    }

    @Test fun `buildArgsLiteral handles scalar null arg`() {
        assertEquals("null", buildArgsLiteral(JSONArray().put(JSONObject.NULL)))
    }

    @Test fun `buildArgsLiteral handles scalar string arg`() {
        assertEquals("\"hello\"", buildArgsLiteral(JSONArray().put("hello")))
    }

    @Test fun `buildArgsLiteral handles string with special characters`() {
        assertEquals("\"he said \\\"hi\\\"\"", buildArgsLiteral(JSONArray().put("he said \"hi\"")))
    }

    // ── buildArgsLiteral: 리스트 타입 ────────────────────────────────────

    @Test fun `buildArgsLiteral converts int list to listOf`() {
        assertEquals("listOf(1, 2, 3)", buildArgsLiteral(JSONArray().put(JSONArray().put(1).put(2).put(3))))
    }

    @Test fun `buildArgsLiteral converts float list to listOf`() {
        assertEquals("listOf(1.5, 2.5)", buildArgsLiteral(JSONArray().put(JSONArray().put(1.5).put(2.5))))
    }

    @Test fun `buildArgsLiteral converts bool list to listOf`() {
        assertEquals("listOf(true, false)", buildArgsLiteral(JSONArray().put(JSONArray().put(true).put(false))))
    }

    @Test fun `buildArgsLiteral converts list with null to listOf`() {
        assertEquals("listOf(null, 1)", buildArgsLiteral(JSONArray().put(JSONArray().put(JSONObject.NULL).put(1))))
    }

    @Test fun `buildArgsLiteral converts mixed type list to listOf`() {
        assertEquals("listOf(1, \"hello\", true)", buildArgsLiteral(JSONArray().put(JSONArray().put(1).put("hello").put(true))))
    }

    @Test fun `buildArgsLiteral converts nested list to listOf`() {
        val inner1 = JSONArray().put(1).put(2)
        val inner2 = JSONArray().put(3).put(4)
        assertEquals("listOf(listOf(1, 2), listOf(3, 4))", buildArgsLiteral(JSONArray().put(JSONArray().put(inner1).put(inner2))))
    }

    @Test fun `buildArgsLiteral handles empty list arg`() {
        assertEquals("listOf()", buildArgsLiteral(JSONArray().put(JSONArray())))
    }

    @Test fun `buildArgsLiteral handles list and scalar mixed args`() {
        assertEquals("listOf(1, 2, 3), 2", buildArgsLiteral(JSONArray().put(JSONArray().put(1).put(2).put(3)).put(2)))
    }

    // ── buildArgsLiteral: 맵 타입 ────────────────────────────────────────

    @Test fun `buildArgsLiteral converts map with int values to mapOf`() {
        val obj = JSONObject().put("a", 1).put("b", 2)
        val result = buildArgsLiteral(JSONArray().put(obj))
        assert(result == "mapOf(\"a\" to 1, \"b\" to 2)" || result == "mapOf(\"b\" to 2, \"a\" to 1)") {
            "unexpected: $result"
        }
    }

    @Test fun `buildArgsLiteral converts map with string values to mapOf`() {
        val obj = JSONObject().put("key", "value")
        assertEquals("mapOf(\"key\" to \"value\")", buildArgsLiteral(JSONArray().put(obj)))
    }

    @Test fun `buildArgsLiteral converts map with nested list value to mapOf`() {
        val obj = JSONObject().put("nums", JSONArray().put(1).put(2))
        assertEquals("mapOf(\"nums\" to listOf(1, 2))", buildArgsLiteral(JSONArray().put(obj)))
    }

    @Test fun `buildArgsLiteral handles map and scalar mixed args`() {
        val obj = JSONObject().put("a", 1)
        val result = buildArgsLiteral(JSONArray().put(obj).put(42))
        assertEquals("mapOf(\"a\" to 1), 42", result)
    }

    // ── judgeToJsonLiteral: 반환값 직렬화 ────────────────────────────────

    @Test fun `judgeToJsonLiteral serializes int`() {
        assertEquals("2", judgeToJsonLiteral(2))
    }

    @Test fun `judgeToJsonLiteral serializes float`() {
        assertEquals("2.5", judgeToJsonLiteral(2.5))
    }

    @Test fun `judgeToJsonLiteral serializes true`() {
        assertEquals("true", judgeToJsonLiteral(true))
    }

    @Test fun `judgeToJsonLiteral serializes false`() {
        assertEquals("false", judgeToJsonLiteral(false))
    }

    @Test fun `judgeToJsonLiteral serializes null`() {
        assertEquals("null", judgeToJsonLiteral(null))
    }

    @Test fun `judgeToJsonLiteral serializes string`() {
        assertEquals("\"hello\"", judgeToJsonLiteral("hello"))
    }

    @Test fun `judgeToJsonLiteral serializes string with special characters`() {
        assertEquals("\"line1\\nline2\"", judgeToJsonLiteral("line1\nline2"))
    }

    @Test fun `judgeToJsonLiteral serializes int list`() {
        assertEquals("[1, 2, 3]", judgeToJsonLiteral(listOf(1, 2, 3)))
    }

    @Test fun `judgeToJsonLiteral serializes nested list`() {
        assertEquals("[[1, 2], [3, 4]]", judgeToJsonLiteral(listOf(listOf(1, 2), listOf(3, 4))))
    }

    @Test fun `judgeToJsonLiteral serializes list with null`() {
        assertEquals("[null, 1]", judgeToJsonLiteral(listOf(null, 1)))
    }

    @Test fun `judgeToJsonLiteral serializes map`() {
        assertEquals("{\"a\":1}", judgeToJsonLiteral(mapOf("a" to 1)))
    }

    @Test fun `judgeToJsonLiteral serializes map with list value`() {
        assertEquals("{\"nums\":[1, 2]}", judgeToJsonLiteral(mapOf("nums" to listOf(1, 2))))
    }

    // ── 생성된 소스 실행: primitive 배열 직렬화 end-to-end ────────────────

    private fun runGeneratedSolution(code: String, argsLiteral: String): JSONObject {
        assumeTrue(
            runCatching { ProcessBuilder("kotlinc", "-version").start().waitFor() == 0 }.getOrDefault(false),
            "kotlinc not available"
        )
        val tmpDir = Files.createTempDirectory("kotlin-runner-test").toFile()
        try {
            val resultFile = File(tmpDir, "result.txt")
            val source = buildSolutionSource(code, argsLiteral, resultFile.absolutePath)
            val sourceFile = File(tmpDir, "solution.kt")
            sourceFile.writeText(source)
            val jarFile = File(tmpDir, "solution.jar")

            val compile = ProcessBuilder("kotlinc", sourceFile.absolutePath, "-include-runtime", "-d", jarFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            compile.waitFor()

            ProcessBuilder("java", "-jar", jarFile.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor()

            return JSONObject(resultFile.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test fun `generated code serializes IntArray without memory address`() {
        val result = runGeneratedSolution(
            code = "fun solution(n: Int): IntArray = intArrayOf(1, 2, 3)",
            argsLiteral = "0",
        )
        assertEquals("OK", result.getString("status"))
        assertEquals("[1,2,3]", result.get("output").toString().replace(" ", ""))
    }

    @Test fun `generated code serializes LongArray without memory address`() {
        val result = runGeneratedSolution(
            code = "fun solution(n: Int): LongArray = longArrayOf(10L, 20L, 30L)",
            argsLiteral = "0",
        )
        assertEquals("OK", result.getString("status"))
        assertEquals("[10,20,30]", result.get("output").toString().replace(" ", ""))
    }

    @Test fun `generated code serializes IntArray not as memory address`() {
        val result = runGeneratedSolution(
            code = "fun solution(n: Int): IntArray = intArrayOf(1, 2, 3)",
            argsLiteral = "0",
        )
        val output = result.get("output").toString()
        assert(!output.contains("@")) { "메모리 주소 형태로 출력됨: $output" }
    }

    // ── judgeToJsonLiteral: primitive 배열 직렬화 ─────────────────────────

    @Test fun `judgeToJsonLiteral serializes IntArray`() {
        assertEquals("[1, 2, 3]", judgeToJsonLiteral(intArrayOf(1, 2, 3)))
    }

    @Test fun `judgeToJsonLiteral serializes LongArray`() {
        assertEquals("[1, 2, 3]", judgeToJsonLiteral(longArrayOf(1L, 2L, 3L)))
    }

    @Test fun `judgeToJsonLiteral serializes DoubleArray`() {
        assertEquals("[1.0, 2.5]", judgeToJsonLiteral(doubleArrayOf(1.0, 2.5)))
    }

    @Test fun `judgeToJsonLiteral serializes BooleanArray`() {
        assertEquals("[true, false]", judgeToJsonLiteral(booleanArrayOf(true, false)))
    }

    @Test fun `judgeToJsonLiteral serializes empty IntArray`() {
        assertEquals("[]", judgeToJsonLiteral(intArrayOf()))
    }
}
