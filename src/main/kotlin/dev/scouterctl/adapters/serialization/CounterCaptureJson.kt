package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class CounterExportQueryDto(val from: Long, val to: Long, val timezone: String, val objHash: Int,
    val counter: String, val chunkMinutes: Int)
@Serializable
data class CounterChunkFileDto(val name: String, val sha256: String, val records: Int)
@Serializable
data class CounterChunkDto(val query: CounterHistoryQueryDto, val target: CounterTargetDto?,
    val counter: CounterDefinitionDto?, val status: String, val file: CounterChunkFileDto)
@Serializable
data class CounterCaptureManifestDto(
    val schemaVersion: String, val source: ServerInfoDto, val endpoint: String, val query: CounterExportQueryDto,
    val chunks: List<CounterChunkDto>, val executionComplete: Boolean,
    val resolution: String = "stored-realtime", val intervalMs: Long? = null,
    val missingValues: String = "omitted", val metadataScope: String = "current-collector-definition",
    val timezoneVerification: String = "operator-declared", val upstreamCoverage: String = "unknown", val sampling: String = "unknown",
)

fun CounterCaptureManifest.toDto() = CounterCaptureManifestDto(schemaVersion, source.toDto(), endpoint,
    CounterExportQueryDto(query.from, query.to, query.timezone, query.objHash, query.counter, query.chunkMinutes),
    chunks.map { CounterChunkDto(it.query.toDto(), it.target?.toDto(), it.counter?.toDto(), it.status.name.lowercase(),
        CounterChunkFileDto(it.file.name, it.file.sha256, it.file.records)) }, executionComplete)

fun CounterCaptureManifestDto.toModel(): CounterCaptureManifest {
    if (resolution != "stored-realtime" || intervalMs != null || missingValues != "omitted" ||
        metadataScope != "current-collector-definition" || timezoneVerification != "operator-declared" ||
        upstreamCoverage != "unknown" || sampling != "unknown") {
        throw OperationFailure(ErrorCode.IO_ERROR, "Invalid counter capture metadata")
    }
    return CounterCaptureManifest(schemaVersion, source.toModel(), endpoint,
        CounterExportQuery(query.from, query.to, query.timezone, query.objHash, query.counter, query.chunkMinutes),
        chunks.map { chunk ->
            val q = chunk.query
            CounterChunk(CounterHistoryQuery(q.date, q.from, q.to, q.timezone, q.objHash, q.counter),
                chunk.target?.let { CounterTarget(it.objHash, it.objName, it.objType) },
                chunk.counter?.let { CounterDefinition(it.name, it.displayName, it.unit) },
                when (chunk.status) {
                    "available" -> CounterChunkStatus.AVAILABLE
                    "object_unavailable" -> CounterChunkStatus.OBJECT_UNAVAILABLE
                    else -> throw OperationFailure(ErrorCode.IO_ERROR, "Invalid counter chunk status")
                }, CounterChunkFile(chunk.file.name, chunk.file.sha256, chunk.file.records))
        }, executionComplete)
}
