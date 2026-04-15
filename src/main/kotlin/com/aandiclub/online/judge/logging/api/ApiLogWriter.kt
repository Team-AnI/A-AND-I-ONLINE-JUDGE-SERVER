package com.aandiclub.online.judge.logging.api

interface ApiLogWriter {
    fun write(entry: ApiLogEntry)
}
