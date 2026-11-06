package dev.scouterctl.cli

import dev.scouterctl.core.*

import java.nio.file.Path

sealed interface CommandRequest
sealed interface ContextRequest : CommandRequest {
    data object ListContexts : ContextRequest
    data class Add(val name: String?, val host: String, val port: Int, val user: String?) : ContextRequest
    data class Use(val name: String?) : ContextRequest
}
sealed interface OfflineRequest : CommandRequest {
    data class Inspect(val directory: Path, val txid: Long, val includeText: Boolean) : OfflineRequest
    data class Search(val directory: Path, val filter: EvidenceFilter, val includeText: Boolean) : OfflineRequest
    data class Summary(val directory: Path, val options: ServiceSummaryOptions) : OfflineRequest
    data class Compare(val before: Path, val after: Path, val options: ComparisonOptions) : OfflineRequest
}
sealed interface OnlineRequest : CommandRequest {
    data class Doctor(val login: Boolean) : OnlineRequest
    data object CollectorStatus : OnlineRequest
    data class CollectorConfig(val key: String?) : OnlineRequest
    data class AgentStatus(val objHash: Int) : OnlineRequest
    data class AgentConfig(val objHash: Int, val key: String?) : OnlineRequest
    data object Objects : OnlineRequest
    data class Profile(val date: String, val txid: Long, val includeText: Boolean) : OnlineRequest
    data class Search(val query: Query, val maxPages: Int) : OnlineRequest
    data class Capture(val query: Query, val directory: Path, val resume: Boolean,
        val maxPages: Int, val includeProfiles: Boolean, val includeText: Boolean) : OnlineRequest
}
