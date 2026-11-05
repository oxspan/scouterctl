package dev.scouterctl.application

import dev.scouterctl.core.*

fun captureEvidence(source: ObservationSource, query: Query, request: CaptureOptions, store: EvidenceRepository): EvidenceManifest {
    query.validate()
    if (query.pageSize > 100) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Profile capture page-size must be 1..100")
    val reader = EvidenceReader(source, query.date, request.includeText)
    var manifest = if (request.resume) store.load().also {
        if (it.schemaVersion != "scouterctl.capture/v3") {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Resume requires a v3 profile capture; preserve this capture and start a new directory")
        }
        if (it.source != source.info || it.query != query || it.endpoint != request.endpoint || it.textIncluded != request.includeText) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Resume source, query or text policy differs from manifest")
        }
        store.validate(it)
    } else {
        store.create()
        EvidenceManifest(source = source.info, endpoint = request.endpoint, query = query, textIncluded = request.includeText)
            .also(store::save)
    }
    try {
        for (index in manifest.pages.indices) {
            if (manifest.pages[index].unavailableProfiles == 0) continue
            val records = store.readPage(manifest, index).map { record ->
                if (record.profileStatus == ProfileAvailability.UNAVAILABLE) reader.fetch(record.xlog)
                else record
            }
            val updated = store.writePage(index, records)
            manifest = manifest.copy(pages = manifest.pages.mapIndexed { i, page -> if (i == index) updated else page }, lastFailure = null)
            store.save(manifest)
        }
        if (manifest.executionComplete) {
            manifest = manifest.copy(lastFailure = null)
            store.save(manifest)
            return manifest
        }
        val ids = mutableSetOf<String>()
        manifest.pages.indices.forEach { index -> store.readPage(manifest, index).forEach { ids.add(it.xlog.txid) } }
        repeat(request.maxPages) {
            if (manifest.pages.size >= 10000) throw OperationFailure(ErrorCode.PARTIAL_RESULT, "Evidence capture limit of 10000 pages reached")
            val page = source.page(query, manifest.cursor)
            val records = page.records.map { row ->
                if (!ids.add(row.txid)) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Duplicate transaction in capture")
                reader.fetch(row)
            }
            val saved = store.writePage(manifest.pages.size, records)
            manifest = manifest.copy(pages = manifest.pages + saved, cursor = page.cursor,
                executionComplete = !page.hasMore, lastFailure = null)
            store.save(manifest)
            if (!page.hasMore) return manifest
        }
        return manifest
    } catch (error: OperationFailure) {
        if (error !is UnexpectedFailure) store.save(manifest.copy(lastFailure = error.code))
        throw error

    }
}

private class EvidenceReader(private val source: ObservationSource, private val date: String, private val includeText: Boolean) {
    fun fetch(xlog: XLogRecord): EvidenceRecord {
        return try {
            EvidenceRecord(xlog, ProfileAvailability.AVAILABLE, source.profile(date, xlog.txid.toLong(), includeText))
        } catch (error: OperationFailure) {
            if (error.code != ErrorCode.NOT_FOUND) throw error
            EvidenceRecord(xlog, ProfileAvailability.UNAVAILABLE)
        }
    }
}
