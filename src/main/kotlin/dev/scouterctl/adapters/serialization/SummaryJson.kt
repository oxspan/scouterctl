package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class ServiceSortDto(val option: String) {
    @SerialName("errors") ERRORS("errors"),
    @SerialName("elapsed") ELAPSED("elapsed"),
    @SerialName("requests") REQUESTS("requests")
}

@Serializable
data class RepresentativeRequestDto(val txid: String, val endTime: Long, val elapsedMs: Int, val profileStatus: ProfileAvailabilityDto)

@Serializable
data class ServiceAggregateDto(
    val serviceHash: Int, val serviceName: String?, val nameConflict: Boolean,
    val requests: Int, val errors: Int, val errorRate: Double,
    val elapsedTotalMs: Long, val averageElapsedMs: Double, val maxElapsedMs: Int,
    val unavailableProfiles: Int, val unknownServiceNames: Int,
    val slowest: RepresentativeRequestDto, val slowestError: RepresentativeRequestDto?,
)

@Serializable
data class ServiceSummaryDto(
    val source: ServerInfoDto, val query: QueryDto,
    val captureExecutionComplete: Boolean, val lastFailure: ErrorCodeDto?,
    val textStored: Boolean, val textIncluded: Boolean,
    val upstreamCoverage: String, val sampling: String,
    val capturedRecords: Int, val capturedErrors: Int, val unavailableProfiles: Int, val unknownServiceNames: Int,
    val totalServices: Int, val limit: Int, val sort: ServiceSortDto,
    val services: List<ServiceAggregateDto>, val truncated: Boolean,
    val basis: String = "captured-xlogs",
)

fun ServiceSort.toDto(): ServiceSortDto = ServiceSortDto.valueOf(name)

fun ServiceSortDto.toModel(): ServiceSort = ServiceSort.valueOf(name)

fun RepresentativeRequest.toDto(): RepresentativeRequestDto = RepresentativeRequestDto(txid, endTime, elapsedMs, profileStatus.toDto())

fun RepresentativeRequestDto.toModel(): RepresentativeRequest = RepresentativeRequest(txid, endTime, elapsedMs, profileStatus.toModel())

fun ServiceAggregate.toDto(): ServiceAggregateDto = ServiceAggregateDto(
    serviceHash,
    serviceName,
    nameConflict,
    requests,
    errors,
    errorRate,
    elapsedTotalMs,
    averageElapsedMs,
    maxElapsedMs,
    unavailableProfiles,
    unknownServiceNames,
    slowest.toDto(),
    slowestError?.toDto(),
)

fun ServiceAggregateDto.toModel(): ServiceAggregate = ServiceAggregate(
    serviceHash,
    serviceName,
    nameConflict,
    requests,
    errors,
    errorRate,
    elapsedTotalMs,
    averageElapsedMs,
    maxElapsedMs,
    unavailableProfiles,
    unknownServiceNames,
    slowest.toModel(),
    slowestError?.toModel(),
)

fun ServiceSummary.toDto(): ServiceSummaryDto = ServiceSummaryDto(
    source.toDto(),
    query.toDto(),
    captureExecutionComplete,
    lastFailure?.toDto(),
    textStored,
    textIncluded,
    upstreamCoverage,
    sampling,
    capturedRecords,
    capturedErrors,
    unavailableProfiles,
    unknownServiceNames,
    totalServices,
    limit,
    sort.toDto(),
    services.map { it.toDto() },
    truncated,
    basis,
)

fun ServiceSummaryDto.toModel(): ServiceSummary = ServiceSummary(
    source.toModel(),
    query.toModel(),
    captureExecutionComplete,
    lastFailure?.toModel(),
    textStored,
    textIncluded,
    upstreamCoverage,
    sampling,
    capturedRecords,
    capturedErrors,
    unavailableProfiles,
    unknownServiceNames,
    totalServices,
    limit,
    sort.toModel(),
    services.map { it.toModel() },
    truncated,
    basis,
)
