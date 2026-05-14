package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.logging.api.ApiLogContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice(basePackages = ["com.aandiclub.online.judge.api.v2"])
open class GlobalExceptionHandler {

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleBindException(
        ex: WebExchangeBindException,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<Nothing>> {
        exchange.apiLogContext()?.recordException(ex, handled = true)
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
    fun handleInputException(
        ex: ServerWebInputException,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<Nothing>> {
        exchange.apiLogContext()?.recordException(ex, handled = true)
        val errorCode = if (ex.containsMessage("language")) {
            V2ErrorCode.LANGUAGE_NOT_SUPPORTED
        } else {
            V2ErrorCode.JUDGE_VALIDATION_FAILED
        }
        return ResponseEntity.badRequest().body(
            V2ApiResponses.error(
                errorCode = errorCode,
                message = ex.reason ?: "Invalid request input",
                alert = errorCode.defaultAlert,
            )
        )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(
        ex: ResponseStatusException,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<Nothing>> {
        exchange.apiLogContext()?.recordException(ex, handled = true)
        val status = ex.statusCode
        val (errorCode, value, alert) = when (status.value()) {
            400 -> Triple(V2ErrorCode.JUDGE_VALIDATION_FAILED, "request", "요청 값을 다시 확인해주세요.")
            401 -> Triple(V2ErrorCode.JUDGE_AUTH_MISSING_HEADER, "Authenticate", "인증 정보가 올바르지 않습니다.")
            403 -> Triple(V2ErrorCode.JUDGE_FORBIDDEN, "authorization", "해당 요청에 접근할 수 없습니다.")
            404 -> if ((ex.reason ?: "").contains("User not found", ignoreCase = true)) {
                Triple(V2ErrorCode.JUDGE_USER_NOT_FOUND, "publicCode", "해당 사용자를 찾을 수 없습니다.")
            } else {
                Triple(V2ErrorCode.JUDGE_SUBMISSION_NOT_FOUND, "resource", "요청한 리소스를 찾을 수 없습니다.")
            }
            409 -> Triple(V2ErrorCode.JUDGE_RESULT_NOT_READY, "submission", "채점이 아직 완료되지 않았습니다.")
            in 500..599 -> Triple(V2ErrorCode.JUDGE_INTERNAL_SERVER_ERROR, "judge", "요청 처리 중 오류가 발생했습니다.")
            else -> Triple(V2ErrorCode.JUDGE_VALIDATION_FAILED, "request", "요청 값을 다시 확인해주세요.")
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
    fun handleThrowable(
        ex: Throwable,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<Nothing>> {
        exchange.apiLogContext()?.recordException(ex, handled = true)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            V2ApiResponses.error(
                errorCode = V2ErrorCode.JUDGE_INTERNAL_SERVER_ERROR,
                message = ex.message ?: ex::class.simpleName ?: "Internal error",
                alert = "요청 처리 중 오류가 발생했습니다.",
            )
        )
    }

    private fun ServerWebExchange.apiLogContext(): ApiLogContext? = ApiLogContext.get(this)

    private fun Throwable.containsMessage(value: String): Boolean =
        generateSequence(this) { it.cause }
            .flatMap { throwable -> sequenceOf(throwable.message, (throwable as? ServerWebInputException)?.reason) }
            .filterNotNull()
            .any { it.contains(value, ignoreCase = true) }
}
