package dev.scouterctl.application

import dev.scouterctl.core.*

fun realtimeCounters(source: ObservationSource, objHash: Int, name: String?): RealtimeCounters {
    val result = source.realtimeCounters(objHash)
    val counters = result.counters.sortedBy { it.name }
    if (name == null) return result.copy(counters = counters)
    val selected = counters.singleOrNull { it.name == name }
        ?: throw OperationFailure(ErrorCode.NOT_FOUND, "Counter was not found")
    return result.copy(counters = listOf(selected))
}
