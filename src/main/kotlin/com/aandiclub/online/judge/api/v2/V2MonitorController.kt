package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.dto.V2MonitorOverviewData
import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.service.JudgePerformanceMonitorService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v2/monitor")
@Tag(name = "Monitor V2", description = "A&I v2 aggregate monitoring APIs for administrators.")
class V2MonitorController(
    private val judgePerformanceMonitorService: JudgePerformanceMonitorService,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping
    @Operation(
        summary = "Get current snapshots for all monitoring features",
        description = "Returns a one-shot aggregated view of all currently supported monitoring features. This endpoint is admin-only and complements the per-feature SSE stream APIs.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Current monitor snapshots returned successfully.",
                content = [
                    Content(
                        mediaType = "application/json",
                        examples = [
                            ExampleObject(
                                name = "all-features",
                                value = """{"success":true,"data":{"features":{"judge-performance":{"feature":"judge-performance","activeSubmissions":2,"activeTestCases":4}}},"error":null,"timestamp":"2026-04-18T12:40:13.790Z"}""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(responseCode = "403", description = "ADMIN role is required."),
        ],
    )
    fun getAll(): ResponseEntity<V2ApiResponse<V2MonitorOverviewData>> =
        ResponseEntity.ok(
            V2ApiResponses.success(
                V2MonitorOverviewData(
                    features = judgePerformanceMonitorService.currentAll()
                        .mapValues { (_, snapshot) -> objectMapper.valueToTree(snapshot) }
                )
            )
        )
}
