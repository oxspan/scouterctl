package dev.scouterctl.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CounterExportQuery(
    val from: Long, val to: Long, val timezone: String, val objHash: Int,
    val counter: String, val chunkMinutes: Int = 60,
) {
    fun windows(): List<CounterHistoryQuery> {
        try {
            require(from < to && chunkMinutes in 1..1440)
            val start = Instant.ofEpochMilli(from)
            val finish = Instant.ofEpochMilli(to)
            require(Duration.between(start, finish) <= Duration.ofDays(366))
            val zone = ZoneId.of(timezone)
            require(counter.isNotBlank() && counter.length <= 256 && counter.none(Char::isISOControl))
            val result = mutableListOf<CounterHistoryQuery>()
            var cursor = start
            while (cursor < finish) {
                require(result.size < 10000)
                val day = cursor.atZone(zone).toLocalDate()
                val end = minOf(finish, cursor.plusSeconds(chunkMinutes * 60L), day.plusDays(1).atStartOfDay(zone).toInstant())
                result += CounterHistoryQuery(day.format(DateTimeFormatter.BASIC_ISO_DATE), cursor.toEpochMilli(),
                    end.toEpochMilli(), timezone, objHash, counter).also { it.validate() }
                cursor = end
            }
            return result
        } catch (_: IllegalArgumentException) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Use from < to, at most 366 days/10000 chunks, chunk-minutes 1..1440 and an exact counter name")
        } catch (_: java.time.DateTimeException) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Invalid timezone or time window")
        }
    }
}

enum class CounterChunkStatus { AVAILABLE, OBJECT_UNAVAILABLE }
data class CounterChunkFile(val name: String, val sha256: String, val records: Int)
data class CounterChunk(
    val query: CounterHistoryQuery, val target: CounterTarget?, val counter: CounterDefinition?,
    val status: CounterChunkStatus, val file: CounterChunkFile,
)
data class CounterCaptureManifest(
    val schemaVersion: String = "scouterctl.counter-capture/v1",
    val source: ServerInfo, val endpoint: String, val query: CounterExportQuery,
    val chunks: List<CounterChunk> = emptyList(), val executionComplete: Boolean = false,
) {
    fun validate() {
        val windows = query.windows()
        if (schemaVersion != "scouterctl.counter-capture/v1" || chunks.size > windows.size ||
            executionComplete != (chunks.size == windows.size)) damaged()
        chunks.forEachIndexed { index, chunk ->
            if (chunk.query != windows[index] || chunk.file.records !in 0..100000) damaged()
            when (chunk.status) {
                CounterChunkStatus.AVAILABLE -> if (chunk.target?.objHash != query.objHash || chunk.counter?.name != query.counter) damaged()
                CounterChunkStatus.OBJECT_UNAVAILABLE -> if (chunk.target != null || chunk.counter != null || chunk.file.records != 0) damaged()
            }
        }
    }
    private fun damaged(): Nothing = throw OperationFailure(ErrorCode.IO_ERROR, "Counter capture checkpoint is inconsistent")
}
