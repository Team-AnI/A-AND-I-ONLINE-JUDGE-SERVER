package com.aandiclub.online.judge.api.v2.support

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
    val defaultMessage: String,
    val defaultAlert: String = defaultMessage,
) {
    /** Judge / General / 029 */
    JUDGE_RATE_LIMIT_EXCEEDED(
        code = 50029,
        defaultMessage = "Rate limit exceeded",
        defaultAlert = "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
    ),
    /** Judge / Authentication / 001 */
    JUDGE_AUTH_MISSING_HEADER(
        code = 51001,
        defaultMessage = "Missing or invalid Authenticate header",
        defaultAlert = "인증 정보가 올바르지 않습니다.",
    ),
    /** Judge / Authentication / 002 */
    JUDGE_AUTH_INVALID_TOKEN(
        code = 51002,
        defaultMessage = "Invalid JWT",
        defaultAlert = "인증 토큰이 유효하지 않습니다.",
    ),
    /** Judge / Authorization / 001 */
    JUDGE_FORBIDDEN(
        code = 52001,
        defaultMessage = "Forbidden",
        defaultAlert = "해당 요청에 접근할 수 없습니다.",
    ),
    /** Judge / Validation / 001 */
    JUDGE_VALIDATION_FAILED(
        code = 53001,
        defaultMessage = "Validation failed",
        defaultAlert = "요청 값을 다시 확인해주세요.",
    ),
    /** Judge / Business / 001 */
    JUDGE_RESULT_NOT_READY(
        code = 54001,
        defaultMessage = "Submission result is not ready",
        defaultAlert = "채점이 아직 완료되지 않았습니다.",
    ),
    /** Judge / Not Found / 001 */
    JUDGE_SUBMISSION_NOT_FOUND(
        code = 55001,
        defaultMessage = "Submission not found",
        defaultAlert = "요청한 리소스를 찾을 수 없습니다.",
    ),
    /** Judge / Not Found / 002 */
    JUDGE_USER_NOT_FOUND(
        code = 55002,
        defaultMessage = "User not found",
        defaultAlert = "해당 사용자를 찾을 수 없습니다.",
    ),
    /** Common / Validation / 001 */
    COMMON_MISSING_REQUIRED_HEADER(
        code = 93001,
        defaultMessage = "Missing required header",
        defaultAlert = "필수 헤더가 누락되었습니다.",
    ),
    /** Common / Validation / 002 */
    COMMON_INVALID_HEADER(
        code = 93002,
        defaultMessage = "Invalid header",
        defaultAlert = "헤더 값이 올바르지 않습니다.",
    ),
    /** Common / Internal System / 000 */
    COMMON_INTERNAL_ERROR(
        code = 98000,
        defaultMessage = "Internal error",
        defaultAlert = "요청 처리 중 오류가 발생했습니다.",
    ),
}
