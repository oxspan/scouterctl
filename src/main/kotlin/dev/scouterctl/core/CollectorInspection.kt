package dev.scouterctl.core

data class CollectorStatus(val usedHeapBytes: Long, val committedHeapBytes: Long, val serverTimeEpochMs: Long)
