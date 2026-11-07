package dev.scouterctl.application

import dev.scouterctl.core.*

data class CounterExportOptions(val resume: Boolean, val maxChunks: Int, val endpoint: String)

fun exportCounters(source: ObservationSource, query: CounterExportQuery,
    options: CounterExportOptions, store: CounterCaptureRepository): CounterCaptureManifest {
    val windows = query.windows()
    if (options.maxChunks !in 1..10000) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Use max-chunks 1..10000")
    var manifest = if (options.resume) {
        val loaded = store.load()
        if (loaded.query != query || loaded.source != source.info || loaded.endpoint != options.endpoint) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Resume source or query differs from counter capture")
        }
        loaded.validate()
        store.validate(loaded)
        loaded
    } else {
        store.create()
        CounterCaptureManifest(source = source.info, endpoint = options.endpoint, query = query).also(store::save)
    }
    if (manifest.executionComplete) return manifest
    for (window in windows.drop(manifest.chunks.size).take(options.maxChunks)) {
        val history = try { source.counterHistory(window) } catch (error: OperationFailure) {
            if (error.code != ErrorCode.NOT_FOUND) throw error
            null
        }
        val file = store.writeChunk(manifest.chunks.size, history?.points.orEmpty())
        val chunk = CounterChunk(window, history?.target, history?.counter,
            if (history == null) CounterChunkStatus.OBJECT_UNAVAILABLE else CounterChunkStatus.AVAILABLE, file)
        manifest = manifest.copy(chunks = manifest.chunks + chunk, executionComplete = window.to == query.to)
        store.save(manifest)
    }
    return manifest
}
