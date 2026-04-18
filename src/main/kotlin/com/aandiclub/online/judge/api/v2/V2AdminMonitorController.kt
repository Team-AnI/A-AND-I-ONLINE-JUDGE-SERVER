package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.dto.V2AdminMonitorStreamEventData
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.service.JudgePerformanceMonitorService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v2/admin/monitor")
@Tag(name = "Admin Monitor V2", description = "A&I v2 live performance monitoring SSE APIs for administrators.")
class V2AdminMonitorController(
    private val judgePerformanceMonitorService: JudgePerformanceMonitorService,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping("/{feature}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Stream live performance metrics for an admin feature",
        description = "Subscribes to a live SSE feed that pushes a fresh snapshot whenever the monitored feature changes. Use `judge-performance` to monitor current judge throughput, running load, and recent latency metrics.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Monitoring SSE stream opened successfully.",
                content = [
                    Content(
                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                        examples = [
                            ExampleObject(
                                name = "judge-performance",
                                value = "event: snapshot\ndata: {\"success\":true,\"data\":{\"event\":\"snapshot\",\"feature\":\"judge-performance\",\"payload\":{\"activeSubmissions\":2,\"activeTestCases\":4}},\"error\":null,\"timestamp\":\"2026-04-18T12:40:13.790Z\"}",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "403", description = "ADMIN role is required."),
            ApiResponse(responseCode = "404", description = "Unknown monitor feature."),
        ],
    )
    fun streamFeature(
        @Parameter(
            description = "Feature identifier to monitor. Currently `judge-performance` is supported.",
            example = "judge-performance",
        )
        @PathVariable feature: String,
    ): Flow<ServerSentEvent<String>> =
        judgePerformanceMonitorService.stream(feature)
            .map { snapshot ->
                val eventName = "snapshot"
                val wrapped = objectMapper.writeValueAsString(
                    V2ApiResponses.success(
                        V2AdminMonitorStreamEventData(
                            event = eventName,
                            feature = snapshot.feature,
                            payload = objectMapper.valueToTree(snapshot),
                        )
                    )
                )
                ServerSentEvent.builder<String>()
                    .event(eventName)
                    .data(wrapped)
                    .build()
            }
}
