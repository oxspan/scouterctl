package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class ComparisonWarningDto {
    @SerialName("traffic-coverage-unverified") TRAFFIC_COVERAGE_UNVERIFIED,
    @SerialName("different-server") DIFFERENT_SERVER,
    @SerialName("different-collector-version") DIFFERENT_COLLECTOR_VERSION,
    @SerialName("different-object") DIFFERENT_OBJECT,
    @SerialName("different-timezone") DIFFERENT_TIMEZONE,
    @SerialName("different-duration") DIFFERENT_DURATION,
    @SerialName("overlapping-windows") OVERLAPPING_WINDOWS,
    @SerialName("incomplete-capture") INCOMPLETE_CAPTURE,
    @SerialName("unavailable-profiles") UNAVAILABLE_PROFILES,
    @SerialName("unresolved-service-names") UNRESOLVED_SERVICE_NAMES,
    @SerialName("service-identity-conflict") SERVICE_IDENTITY_CONFLICT,
}

@Serializable
enum class ServicePresenceDto {
    @SerialName("both") BOTH,
    @SerialName("before-only") BEFORE_ONLY,
    @SerialName("after-only") AFTER_ONLY,
}

@Serializable
data class ComparisonCaptureDto(
    val source: ServerInfoDto, val query: QueryDto, val durationMs: Long,
    val captureExecutionComplete: Boolean, val lastFailure: ErrorCodeDto?, val textStored: Boolean,
    val upstreamCoverage: String, val sampling: String,
    val capturedRecords: Int, val capturedErrors: Int, val unavailableProfiles: Int,
    val unknownServiceNames: Int, val totalServices: Int,
)

@Serializable
data class ServiceDeltaDto(
    val requests: Int, val errors: Int, val errorRatePercentagePoints: Double,
    val elapsedTotalMs: Long, val averageElapsedMs: Double, val maxElapsedMs: Long,
)

@Serializable
data class ComparedServiceDto(
    val serviceHash: Int, val presence: ServicePresenceDto, val identityConflict: Boolean,
    val before: ServiceAggregateDto?, val after: ServiceAggregateDto?, val delta: ServiceDeltaDto?,
)

@Serializable
data class CaptureComparisonDto(
    val before: ComparisonCaptureDto, val after: ComparisonCaptureDto,
    val warnings: List<ComparisonWarningDto>, val textIncluded: Boolean,
    val totalServices: Int, val limit: Int, val truncated: Boolean,
    val services: List<ComparedServiceDto>,
    val basis: String = "captured-xlogs", val matching: String = "service-hash",
    val order: String = "service-hash-asc", val deltaDirection: String = "after-minus-before",
)

fun ComparisonWarning.toDto(): ComparisonWarningDto = ComparisonWarningDto.valueOf(name)

fun ComparisonWarningDto.toModel(): ComparisonWarning = ComparisonWarning.valueOf(name)

fun ServicePresence.toDto(): ServicePresenceDto = ServicePresenceDto.valueOf(name)

fun ServicePresenceDto.toModel(): ServicePresence = ServicePresence.valueOf(name)

fun ComparisonCapture.toDto(): ComparisonCaptureDto = ComparisonCaptureDto(
    source.toDto(),
    query.toDto(),
    durationMs,
    captureExecutionComplete,
    lastFailure?.toDto(),
    textStored,
    upstreamCoverage,
    sampling,
    capturedRecords,
    capturedErrors,
    unavailableProfiles,
    unknownServiceNames,
    totalServices,
)

fun ComparisonCaptureDto.toModel(): ComparisonCapture = ComparisonCapture(
    source.toModel(),
    query.toModel(),
    durationMs,
    captureExecutionComplete,
    lastFailure?.toModel(),
    textStored,
    upstreamCoverage,
    sampling,
    capturedRecords,
    capturedErrors,
    unavailableProfiles,
    unknownServiceNames,
    totalServices,
)

fun ServiceDelta.toDto(): ServiceDeltaDto = ServiceDeltaDto(
    requests,
    errors,
    errorRatePercentagePoints,
    elapsedTotalMs,
    averageElapsedMs,
    maxElapsedMs,
)

fun ServiceDeltaDto.toModel(): ServiceDelta = ServiceDelta(
    requests,
    errors,
    errorRatePercentagePoints,
    elapsedTotalMs,
    averageElapsedMs,
    maxElapsedMs,
)

fun ComparedService.toDto(): ComparedServiceDto = ComparedServiceDto(
    serviceHash,
    presence.toDto(),
    identityConflict,
    before?.toDto(),
    after?.toDto(),
    delta?.toDto(),
)

fun ComparedServiceDto.toModel(): ComparedService = ComparedService(
    serviceHash,
    presence.toModel(),
    identityConflict,
    before?.toModel(),
    after?.toModel(),
    delta?.toModel(),
)

fun CaptureComparison.toDto(): CaptureComparisonDto = CaptureComparisonDto(
    before.toDto(),
    after.toDto(),
    warnings.map { it.toDto() },
    textIncluded,
    totalServices,
    limit,
    truncated,
    services.map { it.toDto() },
    basis,
    matching,
    order,
    deltaDirection,
)

fun CaptureComparisonDto.toModel(): CaptureComparison = CaptureComparison(
    before.toModel(),
    after.toModel(),
    warnings.map { it.toModel() },
    textIncluded,
    totalServices,
    limit,
    truncated,
    services.map { it.toModel() },
    basis,
    matching,
    order,
    deltaDirection,
)
