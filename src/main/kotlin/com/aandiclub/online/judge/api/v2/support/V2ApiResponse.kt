package com.aandiclub.online.judge.api.v2.support

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "A&I v2 common response envelope.")
data class V2ApiResponse<out T>(
    @field:Schema(description = "Whether the request was processed successfully.", example = "true")
    val success: Boolean,
    @field:Schema(description = "Response payload for successful requests.")
    val data: T? = null,
    @field:Schema(description = "Error payload for failed requests.")
    val error: V2ApiError? = null,
    @field:Schema(description = "Response generation timestamp in UTC ISO-8601.")
    val timestamp: Instant = Instant.now(),
)

@Schema(description = "A&I v2 error object.")
data class V2ApiError(
    @field:Schema(description = "5-digit A&I v2 error code.", example = "55001")
    val code: Int,
    @field:Schema(description = "Detailed developer-facing error message.", example = "Submission not found")
    val message: String,
    @field:Schema(description = "Field or contextual value associated with the error.", example = "submissionId")
    val value: String = "",
    @field:Schema(description = "User-facing alert message.", example = "제출 결과를 찾을 수 없습니다.")
    val alert: String = message,
)

object V2ApiResponses {
    fun <T> success(data: T, timestamp: Instant = Instant.now()): V2ApiResponse<T> =
        V2ApiResponse(success = true, data = data, error = null, timestamp = timestamp)

    fun error(
        code: Int,
        message: String,
        value: String = "",
        alert: String = message,
        timestamp: Instant = Instant.now(),
    ): V2ApiResponse<Nothing> =
        V2ApiResponse(success = false, data = null, error = V2ApiError(code, message, value, alert), timestamp = timestamp)

    fun error(
        errorCode: V2ErrorCode,
        value: String = errorCode.value,
        message: String = errorCode.defaultMessage,
        alert: String = errorCode.defaultAlert,
        timestamp: Instant = Instant.now(),
    ): V2ApiResponse<Nothing> =
        error(
            code = errorCode.code,
            message = message,
            value = value,
            alert = alert,
            timestamp = timestamp,
        )
}
