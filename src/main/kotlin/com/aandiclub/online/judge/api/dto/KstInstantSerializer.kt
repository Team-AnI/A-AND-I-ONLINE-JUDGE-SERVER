package com.aandiclub.online.judge.api.dto

import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ser.std.StdSerializer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class KstInstantSerializer : StdSerializer<Instant>(Instant::class.java) {
    override fun serialize(
        value: Instant,
        gen: JsonGenerator,
        serializers: SerializationContext,
    ) {
        gen.writeString(FORMATTER.format(value.atZone(KST_ZONE_ID)))
    }

    companion object {
        private val KST_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
