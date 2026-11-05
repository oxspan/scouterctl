package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class PageFileDto(val name: String, val sha256: String, val records: Int)

@Serializable
data class CaptureManifestDto(
    val schemaVersion: String = "scouterctl.capture/v1",
    val source: ServerInfoDto,
    val endpoint: String,
    val query: QueryDto,
    val cursor: CursorDto = CursorDto(),
    val executionComplete: Boolean = false,
    val upstreamCoverage: String = "unknown",
    val sampling: String = "unknown",
    val timezoneVerification: String = "operator-declared",
    val files: List<PageFileDto> = emptyList(),
)

fun PageFile.toDto(): PageFileDto = PageFileDto(name, sha256, records)

fun PageFileDto.toModel(): PageFile = PageFile(name, sha256, records)

fun CaptureManifest.toDto(): CaptureManifestDto = CaptureManifestDto(
    schemaVersion,
    source.toDto(),
    endpoint,
    query.toDto(),
    cursor.toDto(),
    executionComplete,
    upstreamCoverage,
    sampling,
    timezoneVerification,
    files.map { it.toDto() },
)

fun CaptureManifestDto.toModel(): CaptureManifest = CaptureManifest(
    schemaVersion,
    source.toModel(),
    endpoint,
    query.toModel(),
    cursor.toModel(),
    executionComplete,
    upstreamCoverage,
    sampling,
    timezoneVerification,
    files.map { it.toModel() },
)
