package com.aandiclub.online.judge.config

import com.aandiclub.online.judge.api.AdminSubmissionController
import com.aandiclub.online.judge.api.ProblemSubmissionController
import com.aandiclub.online.judge.api.SubmissionController
import com.aandiclub.online.judge.api.v2.V2AdminSubmissionController
import com.aandiclub.online.judge.api.v2.V2ProblemSubmissionController
import com.aandiclub.online.judge.api.v2.V2SubmissionController
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.security.SecurityScheme as OpenApiSecurityScheme
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
    fun `grouped openapi docs include public server url customizer`() {
        val config = SwaggerConfig(OpenApiProperties(serverUrl = "https://public.example.test"))
        val v1OpenApi = OpenAPI().servers(listOf(Server().url("http://10.0.0.12:8080")))
        val v2OpenApi = OpenAPI().servers(listOf(Server().url("http://10.0.0.12:8080")))

        config.v1GroupedOpenApi().openApiCustomizers.forEach { it.customise(v1OpenApi) }
        config.v2GroupedOpenApi().openApiCustomizers.forEach { it.customise(v2OpenApi) }

        assertEquals(listOf("https://public.example.test"), v1OpenApi.servers.map { it.url })
        assertEquals(listOf("https://public.example.test"), v2OpenApi.servers.map { it.url })
    }

    @Test
    fun `public server url overrides generated servers`() {
        val openApi = OpenAPI().servers(listOf(Server().url("http://10.0.0.12:8080")))
        val configuredPublicUrl = "https://public.example.test"

        SwaggerConfig(
            OpenApiProperties(serverUrl = configuredPublicUrl),
        ).publicServerUrlOpenApiCustomizer().customise(openApi)

        assertEquals(listOf(configuredPublicUrl), openApi.servers.map { it.url })
    }

    @Test
    fun `blank public server url falls back to current origin path`() {
        val openApi = OpenAPI().servers(listOf(Server().url("http://10.0.0.12:8080")))

        SwaggerConfig(
            OpenApiProperties(serverUrl = " "),
        ).publicServerUrlOpenApiCustomizer().customise(openApi)

        assertEquals(listOf("/"), openApi.servers.map { it.url })
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

    @Test
    fun `v2 openapi adds required header parameters to operations`() {
        val config = SwaggerConfig(OpenApiProperties(serverUrl = ""))
        val operation = Operation()
        val openApi = OpenAPI().path("/v2/submissions", PathItem().post(operation))

        config.v2HeaderCustomizer().customise(openApi)

        val headers = operation.parameters.associateBy { it.name }
        assertEquals(setOf("deviceOS", "timestamp", "salt"), headers.keys)
        assertTrue(headers.getValue("deviceOS").required)
        assertTrue(headers.getValue("timestamp").required)
        assertEquals(false, headers.getValue("salt").required)
    }

    @Test
    fun `v2 openapi adds authenticate security scheme and requirement`() {
        val config = SwaggerConfig(OpenApiProperties(serverUrl = ""))
        val operation = Operation()
        val openApi = OpenAPI()
            .components(Components())
            .path("/v2/submissions", PathItem().post(operation))

        config.v2SecurityCustomizer().customise(openApi)

        val scheme = openApi.components.securitySchemes[SwaggerConfig.V2_AUTHENTICATE_SCHEME]
        assertNotNull(scheme)
        assertEquals(OpenApiSecurityScheme.Type.APIKEY, scheme?.type)
        assertEquals(OpenApiSecurityScheme.In.HEADER, scheme?.`in`)
        assertEquals("Authenticate", scheme?.name)
        assertTrue(operation.security.any { it.containsKey(SwaggerConfig.V2_AUTHENTICATE_SCHEME) })
    }
}
