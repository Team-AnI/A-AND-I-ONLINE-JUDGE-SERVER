package com.aandiclub.online.judge.logging.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiLogEntry(
    @field:JsonProperty("@timestamp")
    val timestamp: Instant,
    val level: String,
    val logType: String,
    val message: String,
    val env: String,
    val service: ApiLogService,
    val trace: ApiTrace,
    val http: ApiHttp,
    val headers: ApiHeaders,
    val client: ApiClient,
    val actor: ApiActor,
    val request: ApiRequest,
    val response: ApiResponse,
    val event: ApiEvent? = null,
    val errorDetail: ApiErrorDetail? = null,
    val tags: List<String>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiLogService(
    val name: String,
    val domain: String,
    val domainCode: Int,
    val version: String,
    val instanceId: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiTrace(
    val traceId: String,
    val requestId: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiHttp(
    val method: String,
    val path: String,
    val route: String,
    val statusCode: Int,
    val latencyMs: Long,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiHeaders(
    val deviceOS: String?,
    val Authenticate: String?,
    val timestamp: String?,
    val salt: String?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiClient(
    val ip: String?,
    val userAgent: String?,
    val appVersion: String?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiActor(
    val userId: Any?,
    val role: String?,
    val isAuthenticated: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiRequest(
    val query: Map<String, Any?>,
    val pathVariables: Map<String, Any?>,
    val body: Any?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiResponse(
    val success: Boolean,
    val data: Any?,
    val error: ApiError?,
    val timestamp: Instant,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiError(
    val code: Int,
    val message: String,
    val value: String?,
    val alert: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiEvent(
    val eventType: String,
    val resourceId: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ApiErrorDetail(
    val exceptionClass: String,
    val exceptionMessage: String?,
    val stackTraceHash: String,
    val rootCause: String?,
    val handled: Boolean,
)
