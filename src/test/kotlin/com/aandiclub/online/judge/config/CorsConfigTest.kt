package com.aandiclub.online.judge.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorsConfigTest {

    @Test
    fun `default cors origins include public swagger api host`() {
        val properties = CorsProperties()

        assertTrue(properties.apiAllowedOriginPatterns.contains("https://api.aandiclub.com"))
    }

    @Test
    fun `default cors headers include v2 custom headers`() {
        val properties = CorsProperties()

        assertTrue(properties.allowedHeaders.contains("Authenticate"))
        assertTrue(properties.allowedHeaders.contains("deviceOS"))
        assertTrue(properties.allowedHeaders.contains("timestamp"))
        assertTrue(properties.allowedHeaders.contains("salt"))
    }
}
