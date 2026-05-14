package com.aandiclub.online.judge.logging.api

import com.aandiclub.online.judge.api.JwtExchangeAttributes
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.config.ApiLoggingProperties
import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Component
class ApiLogFactory(
    private val objectMapper: ObjectMapper,
    private val maskingUtil: MaskingUtil,
    private val properties: ApiLoggingProperties,
) {
    fun create(exchange: ServerWebExchange, context: ApiLogContext): ApiLogEntry {
        val path = exchange.request.path.pathWithinApplication().value()
        val route = exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: path
        val statusCode = exchange.response.statusCode?.value() ?: 200
        val response = buildResponse(exchange, context, statusCode)
        val success = statusCode in 200..399 && response.success
        val latencyMs = context.latencyMs()
        val logType = resolveLogType(path, statusCode, success, latencyMs)
        val level = when {
            logType == "API_SLOW" -> "WARN"
            logType == "HEALTH" && success -> "INFO"
            logType == "SECURITY" -> "WARN"
            success -> "INFO"
            statusCode >= 500 -> "ERROR"
            else -> "WARN"
        }
        val message = when {
            logType == "API_SLOW" -> "HTTP request completed slowly"
            logType == "HEALTH" -> "Health check completed"
            success -> "HTTP request completed"
            else -> {
                response.error?.message?.takeIf { it.isNotBlank() }
                    ?.let { summarizeFailure(path, it) }
                    ?: summarizeFailure(path, "Request failed")
            }
        }

        return ApiLogEntry(
            timestamp = Instant.now(),
            level = level,
            logType = logType,
            message = message,
            env = properties.env,
            service = ApiLogService(
                name = properties.serviceName,
                domain = properties.domain,
                domainCode = properties.domainCode,
                version = properties.version,
                instanceId = properties.instanceId,
            ),
            trace = ApiTrace(
                traceId = context.traceId,
                requestId = context.requestId,
            ),
            http = ApiHttp(
                method = exchange.request.method?.name() ?: "UNKNOWN",
                path = path,
                route = route,
                statusCode = statusCode,
                latencyMs = latencyMs,
            ),
            headers = ApiHeaders(
                deviceOS = exchange.request.headers.getFirst("deviceOS"),
                Authenticate = maskingUtil.maskHeaderAuthenticate(
                    exchange.request.headers.getFirst("Authenticate")
                        ?: exchange.request.headers.getFirst("Authorization")
                ),
                timestamp = exchange.request.headers.getFirst("timestamp"),
                salt = maskingUtil.maskValue("salt", exchange.request.headers.getFirst("salt")) as String?,
            ),
            client = ApiClient(
                ip = resolveClientIp(exchange),
                userAgent = exchange.request.headers.getFirst("User-Agent"),
                appVersion = exchange.request.headers.getFirst("appVersion")
                    ?: exchange.request.headers.getFirst("App-Version")
                    ?: exchange.request.headers.getFirst("X-App-Version"),
            ),
            actor = buildActor(exchange),
            request = ApiRequest(
                query = buildQuery(exchange),
                pathVariables = buildPathVariables(exchange),
                body = buildRequestBody(exchange, context),
            ),
            response = response,
            errorDetail = buildErrorDetail(context).takeIf {
                logType == "API_ERROR" || logType == "EVENT_ERROR" || logType == "APP_ERROR" || logType == "SECURITY"
            },
            tags = buildTags(exchange, route, success),
        )
    }

    fun createEvent(
        eventType: String,
        logType: String,
        traceId: String? = null,
        resourceId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        errorCode: V2ErrorCode? = null,
        throwable: Throwable? = null,
    ): ApiLogEntry {
        val success = errorCode == null
        val statusCode = errorCode?.httpStatus?.value() ?: 200
        val context = ApiLogContext(
            traceId = traceId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            requestId = UUID.randomUUID().toString(),
        )
        throwable?.let { context.recordException(it, handled = true) }
        return ApiLogEntry(
            timestamp = Instant.now(),
            level = when {
                success -> "INFO"
                statusCode >= 500 -> "ERROR"
                else -> "WARN"
            },
            logType = logType,
            message = if (success) "$eventType completed" else "$eventType failed",
            env = properties.env,
            service = ApiLogService(
                name = properties.serviceName,
                domain = properties.domain,
                domainCode = properties.domainCode,
                version = properties.version,
                instanceId = properties.instanceId,
            ),
            trace = ApiTrace(
                traceId = context.traceId,
                requestId = context.requestId,
            ),
            http = ApiHttp(
                method = "EVENT",
                path = eventType,
                route = eventType,
                statusCode = statusCode,
                latencyMs = 0,
            ),
            headers = ApiHeaders(
                deviceOS = null,
                Authenticate = null,
                timestamp = null,
                salt = null,
            ),
            client = ApiClient(
                ip = null,
                userAgent = null,
                appVersion = null,
            ),
            actor = ApiActor(
                userId = null,
                role = null,
                isAuthenticated = false,
            ),
            request = ApiRequest(
                query = emptyMap(),
                pathVariables = emptyMap(),
                body = maskingUtil.maskMap(metadata),
            ),
            response = ApiResponse(
                success = success,
                data = if (success) mapOf("eventType" to eventType) else null,
                error = errorCode?.let {
                    ApiError(
                        code = it.code,
                        message = throwable?.message ?: it.defaultMessage,
                        value = it.value,
                        alert = it.defaultAlert,
                    )
                },
                timestamp = Instant.now(),
            ),
            event = ApiEvent(
                eventType = eventType,
                resourceId = resourceId,
                metadata = maskingUtil.maskMap(metadata),
            ),
            errorDetail = buildErrorDetail(context).takeIf { !success },
            tags = listOf(
                properties.domain.sanitizeTag(),
                "event",
                if (success) "success" else "fail",
                eventType.sanitizeTag(),
            ),
        )
    }

    private fun buildActor(exchange: ServerWebExchange): ApiActor {
        val subject = exchange.getAttribute<String>(JwtExchangeAttributes.SUBJECT)?.takeIf { it.isNotBlank() && it != "anonymous" }
        val roles = exchange.getAttribute<Set<String>>(JwtExchangeAttributes.ROLES).orEmpty()
        return ApiActor(
            userId = subject?.toLongOrNull() ?: subject,
            role = roles.firstOrNull(),
            isAuthenticated = subject != null,
        )
    }

    private fun buildQuery(exchange: ServerWebExchange): Map<String, Any?> =
        exchange.request.queryParams.toSingleValueMap()
            .mapValues { (_, value) -> value }
            .let(maskingUtil::maskMap)

    private fun buildPathVariables(exchange: ServerWebExchange): Map<String, Any?> =
        (exchange.getAttribute<Map<String, String>>(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) ?: emptyMap())
            .let(maskingUtil::maskMap)

    private fun buildRequestBody(exchange: ServerWebExchange, context: ApiLogContext): Any? {
        if (isEventStream(exchange.request.headers.getFirst("Content-Type"))) return emptyMap<String, Any>()
        val bytes = context.requestBody()
        if (bytes.isEmpty()) return emptyMap<String, Any>()
        val raw = bytes.toString(Charsets.UTF_8)
        return parseAndMaskJson(raw) ?: mapOf("raw" to raw)
    }

    private fun buildResponse(exchange: ServerWebExchange, context: ApiLogContext, statusCode: Int): ApiResponse {
        val contentType = exchange.response.headers.contentType?.toString()
        val fallbackTimestamp = Instant.now()
        if (isEventStream(contentType)) {
            return ApiResponse(
                success = statusCode in 200..399,
                data = mapOf("stream" to "text/event-stream"),
                error = if (statusCode >= 400) buildFallbackError(statusCode, path = exchange.request.path.pathWithinApplication().value()) else null,
                timestamp = fallbackTimestamp,
            )
        }

        val raw = context.responseBody().toString(Charsets.UTF_8)
        if (raw.isBlank()) {
            return ApiResponse(
                success = statusCode in 200..399,
                data = if (statusCode in 200..399) emptyMap<String, Any>() else null,
                error = if (statusCode >= 400) buildFallbackError(statusCode, exchange.request.path.pathWithinApplication().value(), context) else null,
                timestamp = fallbackTimestamp,
            )
        }

        val node = parseJson(raw)
        if (node != null && node.isObject && node.has("success") && node.has("timestamp")) {
            val success = node.path("success").asBoolean(statusCode in 200..399)
            val timestamp = node.path("timestamp").asText().let(::parseInstantOrNow)
            val data = if (node.has("data")) maskingUtil.maskJson(node.get("data")) else null
            val errorNode = node.get("error")
            val error = if (errorNode == null || errorNode.isNull || errorNode.isMissingNode) {
                null
            } else {
                val fallbackCode = fallbackErrorCode(statusCode)
                ApiError(
                    code = errorNode.path("code").asInt(fallbackCode.code),
                    message = errorNode.path("message").asText(defaultErrorMessage(statusCode, exchange.request.path.pathWithinApplication().value())),
                    value = errorNode.textValueOrNull("value") ?: fallbackCode.value,
                    alert = errorNode.textValueOrNull("alert"),
                )
            }
            return ApiResponse(success = success, data = data, error = error, timestamp = timestamp)
        }

        if (statusCode >= 400) {
            return ApiResponse(
                success = false,
                data = null,
                error = buildErrorFromBody(statusCode, raw, node, exchange.request.path.pathWithinApplication().value(), context),
                timestamp = fallbackTimestamp,
            )
        }

        return ApiResponse(
            success = true,
            data = parseAndMaskJson(raw) ?: mapOf("raw" to raw),
            error = null,
            timestamp = fallbackTimestamp,
        )
    }

    private fun buildErrorFromBody(
        statusCode: Int,
        raw: String,
        node: JsonNode?,
        path: String,
        context: ApiLogContext,
    ): ApiError {
        val message = node.textValueOrNull("message")
            ?: node.textValueOrNull("error")
            ?: context.handledException?.message
            ?: defaultErrorMessage(statusCode, path)
        val fallbackCode = fallbackErrorCode(statusCode)
        val value = node.textValueOrNull("value")
        val alert = node.textValueOrNull("alert") ?: message
        return ApiError(
            code = node?.path("code")?.asInt(fallbackCode.code) ?: fallbackCode.code,
            message = message,
            value = value ?: fallbackCode.value,
            alert = alert,
        )
    }

    private fun buildFallbackError(statusCode: Int, path: String, context: ApiLogContext? = null): ApiError {
        val errorCode = fallbackErrorCode(statusCode)
        return ApiError(
            code = errorCode.code,
            message = context?.handledException?.message ?: defaultErrorMessage(statusCode, path),
            value = errorCode.value,
            alert = errorCode.defaultAlert,
        )
    }

    private fun fallbackErrorCode(statusCode: Int): V2ErrorCode = when (statusCode) {
        400 -> V2ErrorCode.JUDGE_VALIDATION_FAILED
        401 -> V2ErrorCode.JUDGE_AUTH_MISSING_HEADER
        403 -> V2ErrorCode.JUDGE_FORBIDDEN
        404 -> V2ErrorCode.JUDGE_SUBMISSION_NOT_FOUND
        409 -> V2ErrorCode.JUDGE_RESULT_NOT_READY
        429 -> V2ErrorCode.JUDGE_RATE_LIMIT_EXCEEDED
        502 -> V2ErrorCode.EXTERNAL_SYSTEM_ERROR
        504 -> V2ErrorCode.SANDBOX_EXECUTION_TIMEOUT
        else -> V2ErrorCode.JUDGE_INTERNAL_SERVER_ERROR
    }

    private fun defaultErrorMessage(statusCode: Int, path: String): String = when (statusCode) {
        400 -> "Request validation failed"
        401 -> "Authentication failed"
        403 -> "Access denied"
        404 -> "Resource not found"
        409 -> "Request conflict"
        429 -> "Rate limit exceeded"
        else -> "Internal error at $path"
    }

    private fun summarizeFailure(path: String, detail: String): String {
        val segments = path.trim('/').split('/').drop(1)
            .filter { it.isNotBlank() && !it.startsWith("{") }
        val target = segments.lastOrNull()
            ?: segments.firstOrNull()
            ?: "API"
        val normalizedTarget = target.replace('-', ' ').replaceFirstChar { it.uppercase() }
        if (detail.startsWith(normalizedTarget, ignoreCase = true)) {
            return detail
        }
        return "$normalizedTarget failed: $detail"
    }

    private fun resolveLogType(path: String, statusCode: Int, success: Boolean, latencyMs: Long): String = when {
        path == "/actuator/health" -> "HEALTH"
        statusCode == 401 || statusCode == 403 -> "SECURITY"
        !success -> "API_ERROR"
        latencyMs >= properties.slowThresholdMs -> "API_SLOW"
        else -> "API"
    }

    private fun buildErrorDetail(context: ApiLogContext): ApiErrorDetail? {
        val throwable = context.handledException ?: return null
        val rootCause = generateSequence(throwable) { it.cause }.last()
        return ApiErrorDetail(
            exceptionClass = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable",
            exceptionMessage = throwable.message,
            stackTraceHash = stackTraceHash(throwable),
            rootCause = rootCause.message ?: rootCause::class.simpleName,
            handled = context.exceptionHandled,
        )
    }

    private fun stackTraceHash(throwable: Throwable): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = throwable.stackTraceToString().toByteArray()
        return "sha256:" + digest.digest(input).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun buildTags(exchange: ServerWebExchange, route: String, success: Boolean): List<String> {
        val segments = route.trim('/').split('/').filter { it.isNotBlank() }
        val featureIndex = when {
            segments.getOrNull(1) == "admin" -> 2
            else -> 1
        }
        val feature = segments.getOrNull(featureIndex)?.sanitizeTag() ?: "api"
        val detail = when {
            segments.lastOrNull() == "stream" -> "stream"
            segments.lastOrNull() == "me" -> "me"
            exchange.request.method?.name() == "POST" -> "create"
            exchange.request.method?.name() == "GET" && segments.any { it.startsWith("{") && it.endsWith("}") } -> "get"
            exchange.request.method?.name() == "GET" -> "list"
            exchange.request.method?.name() == "DELETE" -> "delete"
            exchange.request.method?.name() == "PATCH" -> "patch"
            exchange.request.method?.name() == "PUT" -> "put"
            else -> "request"
        }
        return listOf(
            properties.domain.sanitizeTag(),
            feature,
            if (success) "success" else "fail",
            detail,
        )
    }

    private fun JsonNode?.textValueOrNull(field: String): String? {
        val value = this?.get(field) ?: return null
        if (value.isNull || value.isMissingNode) return null
        return value.asText()
    }

    private fun parseAndMaskJson(raw: String): Any? = parseJson(raw)?.let(maskingUtil::maskJson)

    private fun parseJson(raw: String): JsonNode? = runCatching { objectMapper.readTree(raw) }.getOrNull()

    private fun parseInstantOrNow(value: String?): Instant = runCatching { Instant.parse(value) }.getOrElse { Instant.now() }

    private fun resolveClientIp(exchange: ServerWebExchange): String? =
        exchange.request.headers.getFirst("X-Forwarded-For")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: exchange.request.remoteAddress?.hostString
            ?: (exchange.request.remoteAddress as? InetSocketAddress)?.address?.hostAddress

    private fun isEventStream(contentType: String?): Boolean =
        contentType?.contains("text/event-stream", ignoreCase = true) == true

    private fun String.sanitizeTag(): String =
        lowercase().replace(Regex("[^a-z0-9._-]"), "-")
}
