package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice(basePackages = ["com.aandiclub.online.judge.api.v2"])
class V2ControllerAdvice {

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleBindException(ex: WebExchangeBindException): ResponseEntity<V2ApiResponse<Nothing>> {
        val fieldError = ex.bindingResult.allErrors.firstOrNull() as? FieldError
        val value = fieldError?.field ?: ex.bindingResult.objectName
        val message = fieldError?.defaultMessage ?: "Validation failed"
        return ResponseEntity.badRequest().body(
            V2ApiResponses.error(
                errorCode = V2ErrorCode.JUDGE_VALIDATION_FAILED,
                message = message,
                value = value,
                alert = "요청 값을 다시 확인해주세요.",
            )
        )
    }

    @ExceptionHandler(ServerWebInputException::class)
    fun handleInputException(ex: ServerWebInputException): ResponseEntity<V2ApiResponse<Nothing>> =
        ResponseEntity.badRequest().body(
            V2ApiResponses.error(
                errorCode = V2ErrorCode.JUDGE_VALIDATION_FAILED,
                message = ex.reason ?: "Invalid request input",
                alert = "요청 값을 다시 확인해주세요.",
            )
        )

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<V2ApiResponse<Nothing>> {
        val status = ex.statusCode
        val (errorCode, value, alert) = when (status.value()) {
            403 -> Triple(V2ErrorCode.JUDGE_FORBIDDEN, "authorization", "해당 요청에 접근할 수 없습니다.")
            404 -> if ((ex.reason ?: "").contains("User not found", ignoreCase = true)) {
                Triple(V2ErrorCode.JUDGE_USER_NOT_FOUND, "publicCode", "해당 사용자를 찾을 수 없습니다.")
            } else {
                Triple(V2ErrorCode.JUDGE_SUBMISSION_NOT_FOUND, "resource", "요청한 리소스를 찾을 수 없습니다.")
            }
            else -> Triple(V2ErrorCode.COMMON_INTERNAL_ERROR, "", "요청 처리 중 오류가 발생했습니다.")
        }
        return ResponseEntity.status(status).body(
            V2ApiResponses.error(
                errorCode = errorCode,
                message = ex.reason ?: status.toString(),
                value = value,
                alert = alert,
            )
        )
    }

    @ExceptionHandler(Throwable::class)
    fun handleThrowable(ex: Throwable): ResponseEntity<V2ApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            V2ApiResponses.error(
                errorCode = V2ErrorCode.COMMON_INTERNAL_ERROR,
                message = ex.message ?: ex::class.simpleName ?: "Internal error",
                alert = "요청 처리 중 오류가 발생했습니다.",
            )
        )
}
