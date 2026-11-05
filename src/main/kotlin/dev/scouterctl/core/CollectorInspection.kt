package dev.scouterctl.core

data class CollectorStatus(val usedHeapBytes: Long, val committedHeapBytes: Long, val serverTimeEpochMs: Long)

data class CollectorSetting(
    val key: String, val value: String?, val defaultValue: String?, val redacted: Boolean,
)
