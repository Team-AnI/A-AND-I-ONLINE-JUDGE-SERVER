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
              "passwordConfirm": "secret",
              "token": "token-value",
              "salt": "salt-value",
              "secret": "client-secret",
              "nested": {
                "accessToken": "access-token",
                "refreshToken": "refresh-token",
                "Authenticate": "Bearer nested-token"
              }
            }
            """.trimIndent()
        )

        val masked = maskingUtil.maskJson(node) as Map<*, *>
        val nested = masked["nested"] as Map<*, *>

        assertEquals("han******", masked["loginId"])
        assertEquals("****", masked["password"])
        assertEquals("****", masked["passwordConfirm"])
        assertEquals("****", masked["token"])
        assertEquals("****", masked["salt"])
        assertEquals("****", masked["secret"])
        assertEquals("****", nested["accessToken"])
        assertEquals("****", nested["refreshToken"])
        assertEquals("****", nested["Authenticate"])
    }

    @Test
    fun `masks authenticate header`() {
        assertEquals("Bearer ****", maskingUtil.maskHeaderAuthenticate("Bearer jwt-token"))
        assertEquals("****", maskingUtil.maskHeaderAuthenticate("Basic abcdef"))
        assertEquals(null, maskingUtil.maskHeaderAuthenticate(null))
    }
}
