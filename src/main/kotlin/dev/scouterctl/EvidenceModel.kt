package dev.scouterctl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProfileAvailability { @SerialName("available") AVAILABLE, @SerialName("unavailable") UNAVAILABLE }

@Serializable
data class EvidenceRecord(val xlog: XLogRecord, val profileStatus: ProfileAvailability, val profile: ProfileRecord? = null)

@Serializable
data class EvidencePage(val name: String, val sha256: String, val records: Int, val unavailableProfiles: Int)

@Serializable
data class EvidenceManifest(
    val schemaVersion: String = "scouterctl.capture/v2",
    val source: ServerInfo, val endpoint: String, val query: Query, val textIncluded: Boolean,
    val cursor: Cursor = Cursor(), val executionComplete: Boolean = false,
    val pages: List<EvidencePage> = emptyList(), val lastFailure: ErrorCode? = null,
    val upstreamCoverage: String = "unknown", val sampling: String = "unknown",
    val timezoneVerification: String = "operator-declared",
) {
    val complete: Boolean get() = executionComplete && pages.all { it.unavailableProfiles == 0 } && lastFailure == null
}

@Serializable
data class Inspection(
    val source: ServerInfo, val query: Query, val captureExecutionComplete: Boolean,
    val unavailableProfiles: Int, val textStored: Boolean, val textIncluded: Boolean,
    val record: EvidenceRecord, val upstreamCoverage: String, val sampling: String,
)
