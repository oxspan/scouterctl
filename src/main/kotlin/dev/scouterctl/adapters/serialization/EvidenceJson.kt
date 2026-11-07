package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class ProfileAvailabilityDto { @SerialName("available") AVAILABLE, @SerialName("unavailable") UNAVAILABLE }

@Serializable
data class EvidenceRecordDto(val xlog: XLogRecordDto, val profileStatus: ProfileAvailabilityDto, val profile: ProfileRecordDto? = null)

@Serializable
data class EvidencePageDto(val name: String, val sha256: String, val records: Int, val unavailableProfiles: Int)

@Serializable
data class EvidenceManifestDto(
    val schemaVersion: String = "scouterctl.capture/v3",
    val source: ServerInfoDto, val endpoint: String, val query: QueryDto, val textIncluded: Boolean,
    val cursor: CursorDto = CursorDto(), val executionComplete: Boolean = false,
    val pages: List<EvidencePageDto> = emptyList(), val lastFailure: ErrorCodeDto? = null,
    val upstreamCoverage: String = "unknown", val sampling: String = "unknown",
    val timezoneVerification: String = "operator-declared",
)

@Serializable
data class InspectionDto(
    val source: ServerInfoDto, val query: QueryDto, val captureExecutionComplete: Boolean,
    val unavailableProfiles: Int, val textStored: Boolean, val textIncluded: Boolean,
    val record: EvidenceRecordDto, val upstreamCoverage: String, val sampling: String,
)

fun ProfileAvailability.toDto(): ProfileAvailabilityDto = ProfileAvailabilityDto.valueOf(name)

fun ProfileAvailabilityDto.toModel(): ProfileAvailability = ProfileAvailability.valueOf(name)

fun EvidenceRecord.toDto(): EvidenceRecordDto = EvidenceRecordDto(xlog.toDto(), profileStatus.toDto(), profile?.toDto())

fun EvidenceRecordDto.toModel(): EvidenceRecord = EvidenceRecord(xlog.toModel(), profileStatus.toModel(), profile?.toModel())

fun EvidencePage.toDto(): EvidencePageDto = EvidencePageDto(name, sha256, records, unavailableProfiles)

fun EvidencePageDto.toModel(): EvidencePage = EvidencePage(name, sha256, records, unavailableProfiles)

fun EvidenceManifest.toDto(): EvidenceManifestDto = EvidenceManifestDto(
    schemaVersion,
    source.toDto(),
    endpoint,
    query.toDto(),
    textIncluded,
    cursor.toDto(),
    executionComplete,
    pages.map { it.toDto() },
    lastFailure?.toDto(),
    upstreamCoverage,
    sampling,
    timezoneVerification,
)

fun EvidenceManifestDto.toModel(): EvidenceManifest = EvidenceManifest(
    schemaVersion,
    source.toModel(),
    endpoint,
    query.toModel(),
    textIncluded,
    cursor.toModel(),
    executionComplete,
    pages.map { it.toModel() },
    lastFailure?.toModel(),
    upstreamCoverage,
    sampling,
    timezoneVerification,
)

fun Inspection.toDto(): InspectionDto = InspectionDto(
    source.toDto(),
    query.toDto(),
    captureExecutionComplete,
    unavailableProfiles,
    textStored,
    textIncluded,
    record.toDto(),
    upstreamCoverage,
    sampling,
)

fun InspectionDto.toModel(): Inspection = Inspection(
    source.toModel(),
    query.toModel(),
    captureExecutionComplete,
    unavailableProfiles,
    textStored,
    textIncluded,
    record.toModel(),
    upstreamCoverage,
    sampling,
)
