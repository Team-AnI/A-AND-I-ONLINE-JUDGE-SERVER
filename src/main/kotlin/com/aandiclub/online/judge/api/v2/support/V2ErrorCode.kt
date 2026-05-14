package com.aandiclub.online.judge.api.v2.support

import org.springframework.http.HttpStatus

/**
 * A&I v2 5-digit error code enum.
 *
 * Format:
 * - 1st digit: service
 *   - 1 Gateway
 *   - 2 Auth
 *   - 3 User
 *   - 4 Report
 *   - 5 Judge
 *   - 6 Blog
 *   - 9 Common
 * - 2nd digit: category
 *   - 0 General
 *   - 1 Authentication
 *   - 2 Authorization
 *   - 3 Validation
 *   - 4 Business
 *   - 5 Not Found
 *   - 6 Conflict
 *   - 7 External System
 *   - 8 Internal System
 * - last 3 digits: detail sequence
 *
 * This project currently defines Judge/Common scoped v2 error codes.
 */
enum class V2ErrorCode(
    val code: Int,
    val value: String,
    val httpStatus: HttpStatus,
    val defaultMessage: String,
    val defaultAlert: String = defaultMessage,
    val severity: V2ErrorSeverity,
) {
    /** Judge / General / 029 */
    JUDGE_RATE_LIMIT_EXCEEDED(
        code = 50029,
        value = "JUDGE_RATE_LIMIT_EXCEEDED",
        httpStatus = HttpStatus.TOO_MANY_REQUESTS,
        defaultMessage = "Rate limit exceeded",
        defaultAlert = "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Authentication / 001 */
    JUDGE_AUTH_MISSING_HEADER(
        code = 51001,
        value = "JUDGE_AUTH_MISSING_HEADER",
        httpStatus = HttpStatus.UNAUTHORIZED,
        defaultMessage = "Missing or invalid Authenticate header",
        defaultAlert = "인증 정보가 올바르지 않습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Authentication / 002 */
    JUDGE_AUTH_INVALID_TOKEN(
        code = 51002,
        value = "JUDGE_AUTH_INVALID_TOKEN",
        httpStatus = HttpStatus.UNAUTHORIZED,
        defaultMessage = "Invalid JWT",
        defaultAlert = "인증 토큰이 유효하지 않습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Authorization / 001 */
    JUDGE_FORBIDDEN(
        code = 52001,
        value = "JUDGE_FORBIDDEN",
        httpStatus = HttpStatus.FORBIDDEN,
        defaultMessage = "Forbidden",
        defaultAlert = "해당 요청에 접근할 수 없습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Validation / 001 */
    JUDGE_VALIDATION_FAILED(
        code = 53001,
        value = "JUDGE_VALIDATION_FAILED",
        httpStatus = HttpStatus.BAD_REQUEST,
        defaultMessage = "Validation failed",
        defaultAlert = "요청 값을 다시 확인해주세요.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Business / 001 */
    JUDGE_RESULT_NOT_READY(
        code = 54001,
        value = "JUDGE_RESULT_NOT_READY",
        httpStatus = HttpStatus.CONFLICT,
        defaultMessage = "Submission result is not ready",
        defaultAlert = "채점이 아직 완료되지 않았습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Business / 301 */
    SUBMISSION_NOT_RUNNABLE(
        code = 54301,
        value = "SUBMISSION_NOT_RUNNABLE",
        httpStatus = HttpStatus.BAD_REQUEST,
        defaultMessage = "submission cannot be executed",
        defaultAlert = "실행할 수 없는 제출입니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Business / 302 */
    LANGUAGE_NOT_SUPPORTED(
        code = 54302,
        value = "LANGUAGE_NOT_SUPPORTED",
        httpStatus = HttpStatus.BAD_REQUEST,
        defaultMessage = "language is not supported",
        defaultAlert = "지원하지 않는 언어입니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Not Found / 001 */
    JUDGE_SUBMISSION_NOT_FOUND(
        code = 55001,
        value = "JUDGE_SUBMISSION_NOT_FOUND",
        httpStatus = HttpStatus.NOT_FOUND,
        defaultMessage = "Submission not found",
        defaultAlert = "요청한 리소스를 찾을 수 없습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / Not Found / 002 */
    JUDGE_USER_NOT_FOUND(
        code = 55002,
        value = "JUDGE_USER_NOT_FOUND",
        httpStatus = HttpStatus.NOT_FOUND,
        defaultMessage = "User not found",
        defaultAlert = "해당 사용자를 찾을 수 없습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Judge / External System / 701 */
    SANDBOX_EXECUTION_FAILED(
        code = 54701,
        value = "SANDBOX_EXECUTION_FAILED",
        httpStatus = HttpStatus.BAD_GATEWAY,
        defaultMessage = "sandbox execution failed",
        defaultAlert = "채점 처리 중 오류가 발생했습니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Judge / External System / 702 */
    SANDBOX_EXECUTION_TIMEOUT(
        code = 54702,
        value = "SANDBOX_EXECUTION_TIMEOUT",
        httpStatus = HttpStatus.GATEWAY_TIMEOUT,
        defaultMessage = "sandbox execution timed out",
        defaultAlert = "채점 시간이 초과되었습니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Judge / External System / 703 */
    JUDGE_WORKER_UNAVAILABLE(
        code = 54703,
        value = "JUDGE_WORKER_UNAVAILABLE",
        httpStatus = HttpStatus.BAD_GATEWAY,
        defaultMessage = "judge worker is unavailable",
        defaultAlert = "채점 서버가 일시적으로 불안정합니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Judge / Internal System / 801 */
    JUDGE_INTERNAL_SERVER_ERROR(
        code = 54801,
        value = "JUDGE_INTERNAL_SERVER_ERROR",
        httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
        defaultMessage = "unexpected judge exception occurred",
        defaultAlert = "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
        severity = V2ErrorSeverity.CRITICAL,
    ),
    /** Judge / Internal System / 802 */
    GRADING_FAILED(
        code = 54802,
        value = "GRADING_FAILED",
        httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
        defaultMessage = "grading failed unexpectedly",
        defaultAlert = "채점 처리 중 오류가 발생했습니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Judge / Internal System / 803 */
    TESTCASE_CREATE_FAILED(
        code = 54803,
        value = "TESTCASE_CREATE_FAILED",
        httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
        defaultMessage = "testcase create failed",
        defaultAlert = "테스트케이스 등록 중 오류가 발생했습니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Judge / Internal System / 804 */
    TESTCASE_UPDATE_FAILED(
        code = 54804,
        value = "TESTCASE_UPDATE_FAILED",
        httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
        defaultMessage = "testcase update failed",
        defaultAlert = "테스트케이스 변경 중 오류가 발생했습니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Judge / Internal System / 805 */
    TESTCASE_DELETE_FAILED(
        code = 54805,
        value = "TESTCASE_DELETE_FAILED",
        httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
        defaultMessage = "testcase delete failed",
        defaultAlert = "테스트케이스 삭제 중 오류가 발생했습니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Common / Validation / 001 */
    COMMON_VALIDATION_ERROR(
        code = 93001,
        value = "COMMON_VALIDATION_ERROR",
        httpStatus = HttpStatus.BAD_REQUEST,
        defaultMessage = "request body/query/path value error",
        defaultAlert = "입력값 형식이 올바르지 않습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Common / Not Found / 001 */
    COMMON_RESOURCE_NOT_FOUND(
        code = 95001,
        value = "COMMON_RESOURCE_NOT_FOUND",
        httpStatus = HttpStatus.NOT_FOUND,
        defaultMessage = "requested resource not found",
        defaultAlert = "요청한 리소스를 찾을 수 없습니다.",
        severity = V2ErrorSeverity.LOW,
    ),
    /** Common / External System / 001 */
    EXTERNAL_SYSTEM_ERROR(
        code = 90701,
        value = "EXTERNAL_SYSTEM_ERROR",
        httpStatus = HttpStatus.BAD_GATEWAY,
        defaultMessage = "external system error occurred",
        defaultAlert = "외부 시스템 처리 중 오류가 발생했습니다.",
        severity = V2ErrorSeverity.HIGH,
    ),
    /** Common / Internal System / 801 */
    INTERNAL_SERVER_ERROR(
        code = 98801,
        value = "INTERNAL_SERVER_ERROR",
        httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
        defaultMessage = "unexpected server exception occurred",
        defaultAlert = "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
        severity = V2ErrorSeverity.CRITICAL,
    ),
}

enum class V2ErrorSeverity {
    LOW,
    HIGH,
    CRITICAL,
}
