package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.config.JwtAuthProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtTokenValidator(
    private val properties: JwtAuthProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val signingKeyBytes = properties.signingKey.toByteArray(StandardCharsets.UTF_8)

    fun validate(token: String): JwtValidationResult {
        val parts = token.split('.')
        if (parts.size != 3) return JwtValidationResult(false, "Malformed JWT")

        val header = decodeJsonPart(parts[0]) ?: return JwtValidationResult(false, "Invalid JWT header")
        val payload = decodeJsonPart(parts[1]) ?: return JwtValidationResult(false, "Invalid JWT payload")

        val alg = header.path("alg").asText("").trim()
        if (alg.isBlank() || alg.equals("none", ignoreCase = true)) {
            return JwtValidationResult(false, "Invalid JWT algorithm")
        }
        if (!verifySignature(alg, "${parts[0]}.${parts[1]}", parts[2])) {
            return JwtValidationResult(false, "Invalid JWT signature")
        }

        val sub = payload.path("sub").asText("").trim()
        if (sub.isBlank()) return JwtValidationResult(false, "JWT subject(sub) is required")

        val expNode = payload.get("exp")
        if (expNode != null && !expNode.isNull) {
            if (!expNode.canConvertToLong()) return JwtValidationResult(false, "Invalid JWT exp claim")
            val nowEpoch = clock.instant().epochSecond
            if (nowEpoch >= expNode.asLong()) return JwtValidationResult(false, "JWT expired")
        }

        val roles = extractRoles(payload)
        if (!isAuthorized(roles)) {
            return JwtValidationResult(false, "Insufficient role (requires ${properties.requiredRole}+)")
        }

        return JwtValidationResult(
            valid = true,
            reason = null,
            subject = sub,
            roles = roles,
        )
    }

    private fun decodeJsonPart(base64Url: String): JsonNode? =
        runCatching {
            val padded = base64Url + "=".repeat((4 - (base64Url.length % 4)) % 4)
            val decoded = Base64.getUrlDecoder().decode(padded)
            objectMapper.readTree(String(decoded, StandardCharsets.UTF_8))
        }.getOrNull()

    private fun verifySignature(alg: String, signingInput: String, signaturePart: String): Boolean {
        val macAlgorithm = when (alg.uppercase()) {
            "HS256" -> "HmacSHA256"
            "HS384" -> "HmacSHA384"
            "HS512" -> "HmacSHA512"
            else -> return false
        }

        val expected = runCatching {
            val mac = Mac.getInstance(macAlgorithm)
            mac.init(SecretKeySpec(signingKeyBytes, macAlgorithm))
            mac.doFinal(signingInput.toByteArray(StandardCharsets.UTF_8))
        }.getOrNull() ?: return false

        val provided = runCatching {
            val padded = signaturePart + "=".repeat((4 - (signaturePart.length % 4)) % 4)
            Base64.getUrlDecoder().decode(padded)
        }.getOrNull() ?: return false

        return MessageDigest.isEqual(provided, expected)
    }

    private fun extractRoles(payload: JsonNode): Set<String> {
        val raw = linkedSetOf<String>()
        collectRoleValues(payload.get("roles"), raw)
        collectRoleValues(payload.get("role"), raw)
        collectRoleValues(payload.get("authorities"), raw)
        collectScopeValues(payload.get("scope"), raw)
        collectScopeValues(payload.get("scp"), raw)
        return raw.map { normalizeRole(it) }.filter { it.isNotBlank() }.toSet()
    }

    private fun collectRoleValues(node: JsonNode?, out: MutableSet<String>) {
        if (node == null || node.isNull) return
        when {
            node.isArray -> node.forEach { collectRoleValues(it, out) }
            node.isTextual -> node.asText().split(',', ' ').map { it.trim() }.filter { it.isNotBlank() }.forEach { out.add(it) }
            else -> out.add(node.asText())
        }
    }

    private fun collectScopeValues(node: JsonNode?, out: MutableSet<String>) {
        if (node == null || node.isNull || !node.isTextual) return
        node.asText().split(' ').map { it.trim() }.filter { it.isNotBlank() }.forEach { out.add(it) }
    }

    private fun normalizeRole(value: String): String = value.trim().uppercase().removePrefix("ROLE_")

    private fun isAuthorized(roles: Set<String>): Boolean {
        if (roles.isEmpty()) return properties.allowWithoutRoleClaim

        val requiredLevel = roleLevel(normalizeRole(properties.requiredRole))
        if (requiredLevel == null) return true

        return roles.any { roleLevel(it)?.let { level -> level >= requiredLevel } ?: true }
    }

    private fun roleLevel(role: String): Int? = when (role) {
        "ANONYMOUS", "GUEST" -> 0
        "USER" -> 1
        "MANAGER" -> 2
        "ADMIN" -> 3
        "SUPER_ADMIN", "ROOT" -> 4
        else -> null
    }
}

data class JwtValidationResult(
    val valid: Boolean,
    val reason: String?,
    val subject: String = "",
    val roles: Set<String> = emptySet(),
)
