package dev.scouterctl.core

data class EvidenceFilter(
    val limit: Int = 20, val minElapsedMs: Int = 0, val errorsOnly: Boolean = false,
    val serviceHash: Int? = null, val serviceName: String? = null,
)

data class SearchRecord(val xlog: XLogRecord, val profileStatus: ProfileAvailability, val service: DictionaryText? = null)

data class EvidenceSummary(
    val capturedRecords: Int, val capturedErrors: Int, val unavailableProfiles: Int, val unknownServiceNames: Int,
    val matchedRecords: Int, val matchedErrors: Int, val matchedElapsedTotalMs: Long, val matchedMaxElapsedMs: Int?,
)

data class EvidenceSearch(
    val source: ServerInfo, val query: Query, val filter: EvidenceFilter,
    val captureExecutionComplete: Boolean, val lastFailure: ErrorCode?,
    val textStored: Boolean, val textIncluded: Boolean,
    val upstreamCoverage: String, val sampling: String,
    val summary: EvidenceSummary, val records: List<SearchRecord>, val truncated: Boolean,
    val order: String = "elapsed-desc,endTime-asc,txid-numeric-asc",
)
