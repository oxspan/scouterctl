package dev.scouterctl.application

import dev.scouterctl.core.*

sealed interface XLogEvent {
    data class Start(val source: ServerInfo, val query: Query) : XLogEvent
    data class Record(val value: XLogRecord) : XLogEvent
    data object Complete : XLogEvent
}

fun streamXLogs(source: ObservationSource, query: Query, maxPages: Int, consume: (XLogEvent) -> Unit) {
    consume(XLogEvent.Start(source.info, query))
    var cursor = Cursor()
    repeat(maxPages) {
        val page = source.page(query, cursor)
        page.records.forEach { consume(XLogEvent.Record(it)) }
        cursor = page.cursor
        if (!page.hasMore) {
            consume(XLogEvent.Complete)
            return
        }
    }
    throw OperationFailure(ErrorCode.PARTIAL_RESULT, "Page budget reached; use capture for resumable extraction")
}
