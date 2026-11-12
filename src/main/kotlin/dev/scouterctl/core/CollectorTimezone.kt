package dev.scouterctl.core

data class CollectorTimezone(val id: String, val utcOffsetSeconds: Int, val referenceTimeEpochMs: Long)
