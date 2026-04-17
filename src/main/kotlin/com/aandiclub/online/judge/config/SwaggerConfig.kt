package com.aandiclub.online.judge.config

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme as OpenApiSecurityScheme
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
            .addOpenApiCustomizer(publicServerUrlOpenApiCustomizer())
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
            .addOpenApiCustomizer(publicServerUrlOpenApiCustomizer())
            .addOpenApiCustomizer(versionedCustomizer(
                version = "v2",
                description = """
                    A&I v2 compatible online judge API.

                    Basic flow:
                    1. Use Swagger `Authorize` to set the `Authenticate` header.
                    2. Send required headers: `deviceOS`, `timestamp`.
                    3. Open `POST /v2/submissions` to create a submission.
                    4. Subscribe to `/v2/submissions/{submissionId}/stream` for wrapped SSE events.
                    5. Poll `GET /v2/submissions/{submissionId}` for the final envelope response.

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
            .addOpenApiCustomizer(v2HeaderCustomizer())
            .addOpenApiCustomizer(v2SecurityCustomizer())
            .build()

    @Bean
    fun publicServerUrlOpenApiCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val publicServerUrl = openApiProperties.serverUrl.trim()

        openApi.servers = if (publicServerUrl.isBlank()) {
            listOf(
                Server()
                    .url("/")
                    .description("Current origin"),
            )
        } else {
            listOf(
                Server()
                    .url(publicServerUrl)
                    .description("Configured by APP_OPENAPI_SERVER_URL"),
            )
        }
    }

    internal fun v2HeaderCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.paths.orEmpty()
            .filterKeys { it.startsWith("/v2/") }
            .values
            .flatMap { it.readOperations() }
            .forEach { operation ->
                operation.ensureHeaderParameter(
                    name = "deviceOS",
                    description = "Client device OS identifier.",
                    required = true,
                    example = "ANDROID",
                )
                operation.ensureHeaderParameter(
                    name = "timestamp",
                    description = "Client request timestamp.",
                    required = true,
                    example = "1712600000",
                )
                operation.ensureHeaderParameter(
                    name = "salt",
                    description = "Optional request salt.",
                    required = false,
                    example = "optional-random-value",
                )
            }
    }

    internal fun v2SecurityCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val securitySchemes = (openApi.components ?: Components()).securitySchemes ?: linkedMapOf()
        securitySchemes[V2_AUTHENTICATE_SCHEME] = OpenApiSecurityScheme()
            .type(OpenApiSecurityScheme.Type.APIKEY)
            .`in`(OpenApiSecurityScheme.In.HEADER)
            .name("Authenticate")
            .description("Bearer JWT token for v2 requests. Format: `Bearer <token>`.")
        openApi.components = (openApi.components ?: Components()).securitySchemes(securitySchemes)

        openApi.paths.orEmpty()
            .filterKeys { it.startsWith("/v2/") }
            .values
            .flatMap { it.readOperations() }
            .forEach { operation ->
                val hasRequirement = operation.security.orEmpty().any { requirement ->
                    requirement.containsKey(V2_AUTHENTICATE_SCHEME)
                }
                if (!hasRequirement) {
                    operation.addSecurityItem(SecurityRequirement().addList(V2_AUTHENTICATE_SCHEME))
                }
            }
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

    private fun Operation.ensureHeaderParameter(
        name: String,
        description: String,
        required: Boolean,
        example: String,
    ) {
        val alreadyExists = parameters.orEmpty().any { parameter ->
            parameter.`in` == "header" && parameter.name.equals(name, ignoreCase = true)
        }
        if (alreadyExists) return

        addParametersItem(
            Parameter()
                .`in`("header")
                .name(name)
                .description(description)
                .required(required)
                .schema(StringSchema())
                .example(example)
        )
    }

    companion object {
        internal const val V2_AUTHENTICATE_SCHEME = "v2Authenticate"
    }
}
