package dev.scouterctl.core

data class ActiveRequest(
    val txid: String, val threadId: String, val state: String, val elapsedMs: Long,
    val threadCpuTimeMs: String?, val service: String?, val threadName: String?,
)

data class ActiveRequests(
    val agent: AgentIdentity, val requests: List<ActiveRequest>, val complete: Boolean, val textIncluded: Boolean,
)

data class AgentThreadDump(val agent: AgentIdentity, val lines: List<String>)
