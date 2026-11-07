package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*

import java.nio.file.Path

internal fun searchFixture(work: Path, text: Boolean = true, empty: Boolean = false): Path {
    val directory = work.resolve("bundle")
    val store = EvidenceStore(directory)
    store.create()
    val from = 1793491200000L
    fun row(id: String, elapsed: Int, service: Int, error: Int, end: Int, name: String?, missing: Boolean = false): EvidenceRecord {
        val xlog = XLogRecord(id, "0", "0", from + end, 42, service, elapsed, error)
        val transaction = if (text) ProfileTransaction("available", DictionaryText(service, name)) else null
        return EvidenceRecord(xlog, if (missing) ProfileAvailability.UNAVAILABLE else ProfileAvailability.AVAILABLE,
            if (missing) null else ProfileRecord(id, emptyList(), transaction = transaction))
    }
    val records = if (empty) emptyList() else listOf(
        row("9007199254740993", 100, 10, 0, 3, "/checkout<GET>"),
        row("10", 500, 20, 7, 2, null, missing = true),
        row("2", 500, 10, 9, 2, "/checkout<GET>"),
        row("-9", 500, 10, 0, 1, null),
        row("3", 10, 20, 0, 4, "/refund<GET>"),
    )
    val pages = records.chunked(2).mapIndexed { index, rows -> store.writePage(index, rows) }
    store.save(EvidenceManifest(source = ServerInfo("fixture", "2.21.3", "UTC", "guest"), endpoint = "offline",
        query = Query("20261101", from, from + 60000, "UTC", 42, 2), textIncluded = text,
        executionComplete = true, pages = pages))
    return directory
}
