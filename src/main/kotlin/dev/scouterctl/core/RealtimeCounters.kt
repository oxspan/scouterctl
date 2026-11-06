package dev.scouterctl.core

enum class CounterAvailability { AVAILABLE, UNAVAILABLE, UNSUPPORTED }

data class CounterReading(
    val name: String, val displayName: String?, val unit: String?,
    val value: String?, val availability: CounterAvailability,
)

data class RealtimeCounters(val agent: AgentIdentity, val counters: List<CounterReading>)
