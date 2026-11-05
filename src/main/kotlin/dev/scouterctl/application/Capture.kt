package dev.scouterctl.application

import dev.scouterctl.core.*

data class CaptureOptions(val resume: Boolean, val maxPages: Int, val endpoint: String,
    val includeText: Boolean = false)

fun capture(source: ObservationSource, query: Query, request: CaptureOptions, store: CaptureRepository): CaptureManifest {
    query.validate()
    var manifest = if (request.resume) {
        val loaded = store.load()
        if (loaded.schemaVersion != "scouterctl.capture/v1" || loaded.query != query ||
            loaded.source != source.info || loaded.endpoint != request.endpoint) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Resume source or query differs from manifest")
        }
        store.validate(loaded)
        loaded
    } else {
        store.create()
        CaptureManifest(source = source.info, endpoint = request.endpoint, query = query).also(store::save)
    }
    if (manifest.executionComplete) return manifest
    repeat(request.maxPages) {
        val page = source.page(query, manifest.cursor)
        val saved = store.writePage(manifest.files.size, page.records)
        manifest = manifest.copy(cursor = page.cursor, executionComplete = !page.hasMore,
            files = manifest.files + saved)
        store.save(manifest)
        if (manifest.executionComplete) return manifest
    }
    return manifest
}
