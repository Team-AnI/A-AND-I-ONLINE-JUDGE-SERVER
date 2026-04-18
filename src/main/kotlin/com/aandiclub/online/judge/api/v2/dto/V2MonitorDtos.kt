package com.aandiclub.online.judge.api.v2.dto

import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.NullNode

@Schema(name = "V2MonitorOverviewData", description = "Current snapshots for all supported monitoring features.")
data class V2MonitorOverviewData(
    @field:Schema(description = "Feature snapshots keyed by feature name.")
    val features: Map<String, JsonNode>,
)

@Schema(name = "V2AdminMonitorStreamEventData", description = "Envelope payload emitted for each admin monitoring SSE event.")
data class V2AdminMonitorStreamEventData(
    @field:Schema(description = "SSE event name.", example = "snapshot")
    val event: String,
    @field:Schema(description = "Monitored feature name.", example = "judge-performance")
    val feature: String,
    @field:Schema(description = "Current feature snapshot encoded as JSON.")
    val payload: JsonNode = NullNode.instance,
)
