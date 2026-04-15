package com.aandiclub.online.judge.logging.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class MaskingUtilTest {
    private val objectMapper = ObjectMapper()
    private val maskingUtil = MaskingUtil()

    @Test
    fun `masks sensitive request fields recursively`() {
        val node = objectMapper.readTree(
            """
            {
              "loginId": "hans1234",
              "password": "secret",
              "nested": {
                "accessToken": "access-token",
                "refreshToken": "refresh-token"
              }
            }
            """.trimIndent()
        )

        val masked = maskingUtil.maskJson(node) as Map<*, *>
        val nested = masked["nested"] as Map<*, *>

        assertEquals("han******", masked["loginId"])
        assertEquals("****", masked["password"])
        assertEquals("****", nested["accessToken"])
        assertEquals("****", nested["refreshToken"])
    }

    @Test
    fun `masks authenticate header`() {
        assertEquals("Bearer ****", maskingUtil.maskHeaderAuthenticate("Bearer jwt-token"))
        assertEquals("****", maskingUtil.maskHeaderAuthenticate("Basic abcdef"))
        assertEquals(null, maskingUtil.maskHeaderAuthenticate(null))
    }
}
