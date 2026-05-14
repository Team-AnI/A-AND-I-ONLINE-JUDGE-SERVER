package com.aandiclub.online.judge.logging.api

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class MaskingUtil {
    fun maskHeaderAuthenticate(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return if (value.startsWith("Bearer ", ignoreCase = true)) {
            "Bearer ****"
        } else {
            "****"
        }
    }

    fun maskJson(node: JsonNode?): Any? {
        if (node == null || node.isNull || node.isMissingNode) return null
        return maskNode(fieldName = null, node = node)
    }

    fun maskMap(value: Map<String, *>): Map<String, Any?> =
        value.entries.associate { (key, item) -> key to maskValue(key, item) }

    fun maskValue(fieldName: String?, value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to maskValue(key?.toString(), item) }
        is Iterable<*> -> value.map { maskValue(fieldName, it) }
        is Array<*> -> value.map { maskValue(fieldName, it) }
        is String -> maskString(fieldName, value)
        else -> value
    }

    private fun maskNode(fieldName: String?, node: JsonNode): Any? = when {
        node.isObject -> {
            val masked = linkedMapOf<String, Any?>()
            val fields = node.properties().iterator()
            while (fields.hasNext()) {
                val entry = fields.next()
                masked[entry.key] = maskNode(entry.key, entry.value)
            }
            masked
        }
        node.isArray -> node.map { maskNode(fieldName, it) }
        node.isTextual -> maskString(fieldName, node.asText())
        node.isIntegralNumber -> node.numberValue()
        node.isFloatingPointNumber -> node.numberValue()
        node.isBoolean -> node.booleanValue()
        else -> node.asText()
    }

    private fun maskString(fieldName: String?, value: String): String = when (fieldName?.lowercase()) {
        in SENSITIVE_FIELDS -> MASKED
        "loginid" -> maskLoginId(value)
        else -> value
    }

    private fun maskLoginId(value: String): String {
        if (value.isBlank()) return value
        return if (value.length <= 3) {
            value.take(1) + "***"
        } else {
            value.take(3) + "******"
        }
    }

    companion object {
        private const val MASKED = "****"
        private val SENSITIVE_FIELDS = setOf(
            "password",
            "passwordconfirm",
            "accesstoken",
            "refreshtoken",
            "authorization",
            "authenticate",
            "token",
            "salt",
            "secret",
        )
    }
}
