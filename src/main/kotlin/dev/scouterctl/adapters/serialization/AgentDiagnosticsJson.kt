package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class ActiveRequestDto(
    val txid: String, val threadId: String, val state: String, val elapsedMs: Long,
    val threadCpuTimeMs: String?, val service: String?, val threadName: String?,
)

@Serializable
data class ActiveRequestsDto(
    val collector: ServerInfoDto, val agent: AgentIdentityDto, val requests: List<ActiveRequestDto>,
    val complete: Boolean, val textIncluded: Boolean,
)

@Serializable
data class AgentThreadDumpDto(
    val collector: ServerInfoDto, val agent: AgentIdentityDto, val lines: List<String>,
    val completeness: String = "unknown",
)

fun ActiveRequests.toDto(source: ServerInfo): ActiveRequestsDto = ActiveRequestsDto(
    source.toDto(), agent.toDto(), requests.map {
        ActiveRequestDto(it.txid, it.threadId, it.state, it.elapsedMs, it.threadCpuTimeMs, it.service, it.threadName)
    }, complete, textIncluded,
)

fun AgentThreadDump.toDto(source: ServerInfo): AgentThreadDumpDto = AgentThreadDumpDto(source.toDto(), agent.toDto(), lines)
