package com.aandiclub.online.judge.config

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.tags.Tag
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "judge.openapi")
data class OpenApiProperties(
    val serverUrl: String = "",
)

@Configuration
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
@EnableConfigurationProperties(OpenApiProperties::class)
class SwaggerConfig(
    private val openApiProperties: OpenApiProperties,
) {
    @Bean
    fun baseOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("AANDI Club Online Judge API")
                .version("all")
                .description(
                    """
                    Asynchronous online judge API for code submissions and live result streaming.

                    Swagger groups are split by API version:
                    - `v1` for legacy clients
                    - `v2` for A&I envelope/header compatible clients

                    Notes:
                    - Use the Swagger UI group selector or `/v3/api-docs/{group}` to inspect one version at a time.
                    - `quiz-101` is preconfigured with 10 sum test cases for quick Swagger testing.
                    """.trimIndent()
                )
        )

    @Bean
    fun v1GroupedOpenApi(): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group("v1")
            .pathsToMatch("/v1/**")
            .addOpenApiCustomizer(versionedCustomizer(
                version = "v1",
                description = """
                    Legacy online judge API for existing clients.

                    Basic flow:
                    1. Authorize with a Bearer JWT in Swagger UI.
                    2. Open `POST /v1/submissions` and pick one of the built-in language examples.
                    3. Subscribe to the SSE stream for live test-case events.
                    4. Poll the final result endpoint when you need the aggregated status.
                    5. Inspect your per-problem history via `GET /v1/problems/{problemId}/submissions/me`.

                    Notes:
                    - Submission creation returns quickly with HTTP 202; actual judging runs asynchronously.
                    - SSE emits `test_case_result`, `done`, and `error` events.
                    - JWT auth is enabled by default and requires at least USER role.
                """.trimIndent(),
                tags = listOf(
                    "Submissions" to "Create submissions, consume live SSE updates, and fetch final verdicts.",
                    "Problem Submissions" to "List the authenticated user's submission history for a problem.",
                    "Admin Submissions" to "View all stored submission records with ADMIN privileges.",
                    "Admin Test Cases" to "View all stored problem test cases with ADMIN privileges.",
                )
            ))
            .build()

    @Bean
    fun v2GroupedOpenApi(): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group("v2")
            .pathsToMatch("/v2/**")
            .addOpenApiCustomizer(versionedCustomizer(
                version = "v2",
                description = """
                    A&I v2 compatible online judge API.

                    Basic flow:
                    1. Send required headers: `deviceOS`, `Authenticate`, `timestamp`.
                    2. Open `POST /v2/submissions` to create a submission.
                    3. Subscribe to `/v2/submissions/{submissionId}/stream` for wrapped SSE events.
                    4. Poll `GET /v2/submissions/{submissionId}` for the final envelope response.

                    Notes:
                    - All responses are wrapped with `success`, `data`, `error`, and `timestamp`.
                    - v2 exists alongside v1 and does not replace legacy endpoints.
                """.trimIndent(),
                tags = listOf(
                    "Submissions V2" to "A&I v2 compatible submission APIs.",
                    "Problem Submissions V2" to "A&I v2 compatible problem submission APIs.",
                    "Admin Submissions V2" to "A&I v2 compatible administrative submission APIs.",
                    "Admin Test Cases V2" to "A&I v2 compatible administrative test case APIs.",
                )
            ))
            .build()

    @Bean
    fun publicServerUrlOpenApiCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val publicServerUrl = openApiProperties.serverUrl.trim()
        if (publicServerUrl.isBlank()) return@OpenApiCustomizer

        openApi.servers = listOf(
            Server()
                .url(publicServerUrl)
                .description("Public API"),
        )
    }

    private fun versionedCustomizer(
        version: String,
        description: String,
        tags: List<Pair<String, String>>,
    ): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.info = (openApi.info ?: Info())
            .title("AANDI Club Online Judge API ($version)")
            .version(version)
            .description(description)

        openApi.tags = tags.map { (name, tagDescription) ->
            Tag()
                .name(name)
                .description(tagDescription)
        }
    }
}
