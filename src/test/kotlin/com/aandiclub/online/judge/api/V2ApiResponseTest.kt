package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class V2ApiResponseTest {
    @Test
    fun `success response keeps v2 envelope shape`() {
        val response = V2ApiResponses.success(mapOf("submissionId" to "sub-1"))

        assertEquals(true, response.success)
        assertEquals(mapOf("submissionId" to "sub-1"), response.data)
        assertNull(response.error)
        assertNotNull(response.timestamp)
    }

    @Test
    fun `error response keeps v2 envelope shape`() {
        val response = V2ApiResponses.error(
            errorCode = V2ErrorCode.JUDGE_INTERNAL_SERVER_ERROR,
            message = "unexpected judge exception occurred",
        )

        assertEquals(false, response.success)
        assertNull(response.data)
        assertEquals(54801, response.error?.code)
        assertEquals("unexpected judge exception occurred", response.error?.message)
        assertEquals("JUDGE_INTERNAL_SERVER_ERROR", response.error?.value)
        assertEquals("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", response.error?.alert)
        assertNotNull(response.timestamp)
    }
}
