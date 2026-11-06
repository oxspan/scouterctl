package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class CounterReadingDto(
    val name: String, val displayName: String?, val unit: String?, val value: String?, val availability: String,
)

@Serializable
data class RealtimeCountersDto(val collector: ServerInfoDto, val agent: AgentIdentityDto, val counters: List<CounterReadingDto>)

fun RealtimeCounters.toDto(source: ServerInfo): RealtimeCountersDto = RealtimeCountersDto(
    source.toDto(), agent.toDto(), counters.map {
        CounterReadingDto(it.name, it.displayName, it.unit, it.value, it.availability.name.lowercase())
    },
)
