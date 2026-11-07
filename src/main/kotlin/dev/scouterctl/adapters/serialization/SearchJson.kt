package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class EvidenceFilterDto(
    val limit: Int = 20, val minElapsedMs: Int = 0, val errorsOnly: Boolean = false,
    val serviceHash: Int? = null, val serviceName: String? = null,
)

@Serializable
data class SearchRecordDto(val xlog: XLogRecordDto, val profileStatus: ProfileAvailabilityDto, val service: DictionaryTextDto? = null)

@Serializable
data class EvidenceSummaryDto(
    val capturedRecords: Int, val capturedErrors: Int, val unavailableProfiles: Int, val unknownServiceNames: Int,
    val matchedRecords: Int, val matchedErrors: Int, val matchedElapsedTotalMs: Long, val matchedMaxElapsedMs: Int?,
)

@Serializable
data class EvidenceSearchDto(
    val source: ServerInfoDto, val query: QueryDto, val filter: EvidenceFilterDto,
    val captureExecutionComplete: Boolean, val lastFailure: ErrorCodeDto?,
    val textStored: Boolean, val textIncluded: Boolean,
    val upstreamCoverage: String, val sampling: String,
    val summary: EvidenceSummaryDto, val records: List<SearchRecordDto>, val truncated: Boolean,
    val order: String = "elapsed-desc,endTime-asc,txid-numeric-asc",
)

fun EvidenceFilter.toDto(): EvidenceFilterDto = EvidenceFilterDto(limit, minElapsedMs, errorsOnly, serviceHash, serviceName)

fun EvidenceFilterDto.toModel(): EvidenceFilter = EvidenceFilter(limit, minElapsedMs, errorsOnly, serviceHash, serviceName)

fun SearchRecord.toDto(): SearchRecordDto = SearchRecordDto(xlog.toDto(), profileStatus.toDto(), service?.toDto())

fun SearchRecordDto.toModel(): SearchRecord = SearchRecord(xlog.toModel(), profileStatus.toModel(), service?.toModel())

fun EvidenceSummary.toDto(): EvidenceSummaryDto = EvidenceSummaryDto(
    capturedRecords,
    capturedErrors,
    unavailableProfiles,
    unknownServiceNames,
    matchedRecords,
    matchedErrors,
    matchedElapsedTotalMs,
    matchedMaxElapsedMs,
)

fun EvidenceSummaryDto.toModel(): EvidenceSummary = EvidenceSummary(
    capturedRecords,
    capturedErrors,
    unavailableProfiles,
    unknownServiceNames,
    matchedRecords,
    matchedErrors,
    matchedElapsedTotalMs,
    matchedMaxElapsedMs,
)

fun EvidenceSearch.toDto(): EvidenceSearchDto = EvidenceSearchDto(
    source.toDto(),
    query.toDto(),
    filter.toDto(),
    captureExecutionComplete,
    lastFailure?.toDto(),
    textStored,
    textIncluded,
    upstreamCoverage,
    sampling,
    summary.toDto(),
    records.map { it.toDto() },
    truncated,
    order,
)

fun EvidenceSearchDto.toModel(): EvidenceSearch = EvidenceSearch(
    source.toModel(),
    query.toModel(),
    filter.toModel(),
    captureExecutionComplete,
    lastFailure?.toModel(),
    textStored,
    textIncluded,
    upstreamCoverage,
    sampling,
    summary.toModel(),
    records.map { it.toModel() },
    truncated,
    order,
)
