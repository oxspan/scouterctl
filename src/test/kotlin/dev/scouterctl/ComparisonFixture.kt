package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*

import java.nio.file.Path

internal fun comparisonFixture(work: Path, before: Boolean): Path {
    val directory = work.resolve(if (before) "before" else "after")
    val store = EvidenceStore(directory)
    store.create()
    val from = 1793491200000L + if (before) 0 else 60000
    val observations = if (before) listOf(Triple(10, 100, 0), Triple(10, 300, 7), Triple(20, 50, 0))
        else listOf(Triple(10, 200, 0), Triple(10, 400, 7), Triple(10, 600, 7), Triple(30, 70, 0))
    val records = observations.mapIndexed { index, (hash, elapsed, error) ->
        val id = (Long.MAX_VALUE - index).toString()
        EvidenceRecord(XLogRecord(id, "0", "0", from + index, 42, hash, elapsed, error),
            ProfileAvailability.AVAILABLE, ProfileRecord(id, emptyList(), transaction = ProfileTransaction(
                "available", DictionaryText(hash, if (hash == 10) "/checkout-한글-café" else "/service-$hash"))))
    }
    store.save(EvidenceManifest(source = ServerInfo("fixture", "2.21.3", "UTC", "guest"), endpoint = "offline",
        query = Query("20261101", from, from + 60000, "UTC", 42, 2), textIncluded = true,
        executionComplete = true, pages = records.chunked(2).mapIndexed { index, rows -> store.writePage(index, rows) }))
    return directory
}

internal fun comparisonRows(directory: Path): List<EvidenceRecord> {
    val store = EvidenceStore(directory)
    val manifest = store.load()
    return manifest.pages.indices.flatMap { store.readPage(manifest, it) }
}

internal fun replaceComparisonRows(directory: Path, rows: List<EvidenceRecord>) {
    val store = EvidenceStore(directory)
    val manifest = store.load()
    store.save(manifest.copy(pages = rows.chunked(manifest.query.pageSize)
        .mapIndexed { index, page -> store.writePage(index, page) }))
}

internal fun compareCli(work: Path, flags: List<String> = emptyList()): Outcome = ContractCli(work).run(
    listOf("inspect", "compare", "--before", work.resolve("before").toString(), "--after", work.resolve("after").toString()) + flags, null)
