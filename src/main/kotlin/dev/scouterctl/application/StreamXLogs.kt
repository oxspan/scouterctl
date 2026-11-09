package dev.scouterctl.application

import dev.scouterctl.core.*

sealed interface XLogEvent {
    data class Start(val source: ServerInfo, val query: Query) : XLogEvent
    data class Record(val value: XLogRecord, val text: Text?) : XLogEvent
    data class Text(val service: DictionaryText, val error: DictionaryText?)
    data object Complete : XLogEvent
}

fun streamXLogs(source: ObservationSource, query: Query, maxPages: Int, includeText: Boolean, consume: (XLogEvent) -> Unit) {
    consume(XLogEvent.Start(source.info, query))
    var cursor = Cursor()
    repeat(maxPages) {
        val page = source.page(query, cursor, includeText)
        page.records.forEach { row ->
            val text = page.text?.let {
                XLogEvent.Text(
                    DictionaryText(row.serviceHash, it.services[row.serviceHash]),
                    if (row.errorHash == 0) null else DictionaryText(row.errorHash, it.errors[row.errorHash]),
                )
            }
            consume(XLogEvent.Record(row, text))
        }
        cursor = page.cursor
        if (!page.hasMore) {
            consume(XLogEvent.Complete)
            return
        }
    }
    throw OperationFailure(ErrorCode.PARTIAL_RESULT, "Page budget reached; use capture for resumable extraction")
}
