package com.aandiclub.online.judge.config

import com.aandiclub.online.judge.api.AdminSubmissionController
import com.aandiclub.online.judge.api.ProblemSubmissionController
import com.aandiclub.online.judge.api.SubmissionController
import com.aandiclub.online.judge.api.v2.V2AdminSubmissionController
import com.aandiclub.online.judge.api.v2.V2ProblemSubmissionController
import com.aandiclub.online.judge.api.v2.V2SubmissionController
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.servers.Server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwaggerConfigTest {

    @Test
    fun `base openapi exposes descriptive info and tags`() {
        val openApi = SwaggerConfig(
            OpenApiProperties(serverUrl = ""),
        ).baseOpenApi()

        assertEquals("AANDI Club Online Judge API", openApi.info.title)
        assertEquals("all", openApi.info.version)
        assertTrue(openApi.info.description.contains("Swagger groups are split by API version"))
    }

    @Test
    fun `swagger config exposes v1 and v2 grouped apis`() {
        val config = SwaggerConfig(OpenApiProperties(serverUrl = ""))

        val v1 = config.v1GroupedOpenApi()
        val v2 = config.v2GroupedOpenApi()

        assertEquals("v1", v1.group)
        assertEquals("v2", v2.group)
    }

    @Test
    fun `public server url overrides generated servers`() {
        val openApi = OpenAPI().servers(listOf(Server().url("http://10.0.0.12:8080")))

        SwaggerConfig(
            OpenApiProperties(serverUrl = "https://api.aandiclub.com"),
        ).publicServerUrlOpenApiCustomizer().customise(openApi)

        assertEquals(listOf("https://api.aandiclub.com"), openApi.servers.map { it.url })
    }

    @Test
    fun `blank public server url keeps generated servers intact`() {
        val original = Server().url("http://10.0.0.12:8080")
        val openApi = OpenAPI().servers(listOf(original))

        SwaggerConfig(
            OpenApiProperties(serverUrl = " "),
        ).publicServerUrlOpenApiCustomizer().customise(openApi)

        assertEquals(listOf("http://10.0.0.12:8080"), openApi.servers.map { it.url })
    }

    @Test
    fun `swagger config declares bearer auth security scheme`() {
        val annotation = SwaggerConfig::class.java.getAnnotation(SecurityScheme::class.java)

        assertNotNull(annotation)
        assertEquals("bearerAuth", annotation.name)
        assertEquals("bearer", annotation.scheme)
        assertEquals("JWT", annotation.bearerFormat)
    }

    @Test
    fun `submission endpoints require bearer auth in openapi`() {
        val annotation = SubmissionController::class.java.getAnnotation(SecurityRequirement::class.java)

        assertNotNull(annotation)
        assertEquals("bearerAuth", annotation.name)
    }

    @Test
    fun `admin submission endpoints require bearer auth in openapi`() {
        val annotation = AdminSubmissionController::class.java.getAnnotation(SecurityRequirement::class.java)

        assertNotNull(annotation)
        assertEquals("bearerAuth", annotation.name)
    }

    @Test
    fun `problem submission endpoints require bearer auth in openapi`() {
        val annotation = ProblemSubmissionController::class.java.getAnnotation(SecurityRequirement::class.java)

        assertNotNull(annotation)
        assertEquals("bearerAuth", annotation.name)
    }

    @Test
    fun `v2 submission endpoints do not require v1 bearer auth annotation`() {
        val annotation = V2SubmissionController::class.java.getAnnotation(SecurityRequirement::class.java)
        assertEquals(null, annotation)
    }

    @Test
    fun `v2 controllers exist as separate swagger groups`() {
        assertNotNull(V2SubmissionController::class.java)
        assertNotNull(V2ProblemSubmissionController::class.java)
        assertNotNull(V2AdminSubmissionController::class.java)
    }
}
