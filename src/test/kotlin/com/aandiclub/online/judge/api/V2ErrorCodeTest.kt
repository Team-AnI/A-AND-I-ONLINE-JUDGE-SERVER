package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.api.v2.support.V2ErrorSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class V2ErrorCodeTest {
    @Test
    fun `all v2 error codes are unique five digit codes with required metadata`() {
        val codes = V2ErrorCode.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
        assertFalse(codes.contains(98000))
        V2ErrorCode.entries.forEach { errorCode ->
            assertTrue(errorCode.code in 10000..99999, "${errorCode.name} must be five digits")
            assertTrue(errorCode.value.isNotBlank(), "${errorCode.name} value is blank")
            assertTrue(errorCode.defaultMessage.isNotBlank(), "${errorCode.name} message is blank")
            assertTrue(errorCode.defaultAlert.isNotBlank(), "${errorCode.name} alert is blank")
            assertNotNull(errorCode.httpStatus, "${errorCode.name} httpStatus is missing")
            assertNotNull(errorCode.severity, "${errorCode.name} severity is missing")
        }
    }

    @Test
    fun `judge and common codes use their service digit`() {
        V2ErrorCode.entries
            .filter { it.name.startsWith("JUDGE_") || it in JUDGE_OPERATION_CODES || it == V2ErrorCode.SUBMISSION_NOT_RUNNABLE || it == V2ErrorCode.LANGUAGE_NOT_SUPPORTED }
            .forEach { assertEquals('5', it.code.toString().first(), "${it.name} must be Judge scoped") }

        V2ErrorCode.entries
            .filter { it in COMMON_CODES }
            .forEach { assertEquals('9', it.code.toString().first(), "${it.name} must be Common scoped") }
    }

    @Test
    fun `spec 201 judge operation codes are defined`() {
        assertSpec(V2ErrorCode.SUBMISSION_NOT_RUNNABLE, 54301, "SUBMISSION_NOT_RUNNABLE", HttpStatus.BAD_REQUEST, V2ErrorSeverity.LOW)
        assertSpec(V2ErrorCode.LANGUAGE_NOT_SUPPORTED, 54302, "LANGUAGE_NOT_SUPPORTED", HttpStatus.BAD_REQUEST, V2ErrorSeverity.LOW)
        assertSpec(V2ErrorCode.SANDBOX_EXECUTION_FAILED, 54701, "SANDBOX_EXECUTION_FAILED", HttpStatus.BAD_GATEWAY, V2ErrorSeverity.HIGH)
        assertSpec(V2ErrorCode.SANDBOX_EXECUTION_TIMEOUT, 54702, "SANDBOX_EXECUTION_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, V2ErrorSeverity.HIGH)
        assertSpec(V2ErrorCode.JUDGE_WORKER_UNAVAILABLE, 54703, "JUDGE_WORKER_UNAVAILABLE", HttpStatus.BAD_GATEWAY, V2ErrorSeverity.HIGH)
        assertSpec(V2ErrorCode.JUDGE_INTERNAL_SERVER_ERROR, 54801, "JUDGE_INTERNAL_SERVER_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, V2ErrorSeverity.CRITICAL)
        assertSpec(V2ErrorCode.GRADING_FAILED, 54802, "GRADING_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, V2ErrorSeverity.HIGH)
        assertSpec(V2ErrorCode.TESTCASE_CREATE_FAILED, 54803, "TESTCASE_CREATE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, V2ErrorSeverity.HIGH)
        assertSpec(V2ErrorCode.TESTCASE_UPDATE_FAILED, 54804, "TESTCASE_UPDATE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, V2ErrorSeverity.HIGH)
        assertSpec(V2ErrorCode.TESTCASE_DELETE_FAILED, 54805, "TESTCASE_DELETE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, V2ErrorSeverity.HIGH)
    }

    @Test
    fun `spec 201 common operation codes are defined`() {
        assertSpec(V2ErrorCode.COMMON_VALIDATION_ERROR, 93001, "COMMON_VALIDATION_ERROR", HttpStatus.BAD_REQUEST, V2ErrorSeverity.LOW)
        assertSpec(V2ErrorCode.COMMON_RESOURCE_NOT_FOUND, 95001, "COMMON_RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, V2ErrorSeverity.LOW)
        assertSpec(V2ErrorCode.EXTERNAL_SYSTEM_ERROR, 90701, "EXTERNAL_SYSTEM_ERROR", HttpStatus.BAD_GATEWAY, V2ErrorSeverity.HIGH)
        assertSpec(V2ErrorCode.INTERNAL_SERVER_ERROR, 98801, "INTERNAL_SERVER_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, V2ErrorSeverity.CRITICAL)
    }

    private fun assertSpec(
        actual: V2ErrorCode,
        code: Int,
        value: String,
        httpStatus: HttpStatus,
        severity: V2ErrorSeverity,
    ) {
        assertEquals(code, actual.code)
        assertEquals(value, actual.value)
        assertEquals(httpStatus, actual.httpStatus)
        assertEquals(severity, actual.severity)
    }

    private companion object {
        val JUDGE_OPERATION_CODES = setOf(
            V2ErrorCode.SANDBOX_EXECUTION_FAILED,
            V2ErrorCode.SANDBOX_EXECUTION_TIMEOUT,
            V2ErrorCode.GRADING_FAILED,
            V2ErrorCode.TESTCASE_CREATE_FAILED,
            V2ErrorCode.TESTCASE_UPDATE_FAILED,
            V2ErrorCode.TESTCASE_DELETE_FAILED,
        )
        val COMMON_CODES = setOf(
            V2ErrorCode.COMMON_VALIDATION_ERROR,
            V2ErrorCode.COMMON_RESOURCE_NOT_FOUND,
            V2ErrorCode.EXTERNAL_SYSTEM_ERROR,
            V2ErrorCode.INTERNAL_SERVER_ERROR,
        )
    }
}
