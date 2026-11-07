package dev.scouterctl.application

import dev.scouterctl.core.*

import java.util.PriorityQueue

fun searchCapture(store: EvidenceRepository, filter: EvidenceFilter, includeText: Boolean): EvidenceSearch {
    if (filter.limit !in 1..1000 || filter.minElapsedMs < 0 || filter.serviceName?.isBlank() == true) {
        throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Use limit 1..1000, nonnegative min-elapsed-ms and a nonblank service name")
    }
    if (filter.serviceName != null && !includeText) {
        throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "--service-name requires --include-text; use --service-hash without text")
    }
    val manifest = store.load()
    if (includeText && !manifest.textIncluded) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Text was not stored in this capture")
    val order = compareByDescending<SearchRecord> { it.xlog.elapsed }
        .thenBy { it.xlog.endTime }.thenBy { it.xlog.txid.toLong() }
    val selected = PriorityQueue(filter.limit, order.reversed())
    var captured = 0
    var errors = 0
    var unavailable = 0
    var unknownNames = 0
    var matched = 0
    var matchedErrors = 0
    var elapsedTotal = 0L
    var maxElapsed: Int? = null
    store.forEachRecord(manifest) { record ->
        val row = record.xlog
        val service = record.profile?.transaction?.service
        captured++
        if (row.errorHash != 0) errors++
        if (record.profileStatus == ProfileAvailability.UNAVAILABLE) unavailable++
        if (service?.text == null) unknownNames++
        if (row.elapsed >= filter.minElapsedMs && (!filter.errorsOnly || row.errorHash != 0) &&
            (filter.serviceHash == null || row.serviceHash == filter.serviceHash) &&
            (filter.serviceName == null || service?.text == filter.serviceName)) {
            matched++
            if (row.errorHash != 0) matchedErrors++
            elapsedTotal += row.elapsed.toLong()
            maxElapsed = maxOf(maxElapsed ?: row.elapsed, row.elapsed)
            val candidate = SearchRecord(row, record.profileStatus, if (includeText) service else null)
            if (selected.size < filter.limit) selected.add(candidate)
            else if (order.compare(candidate, selected.peek()) < 0) { selected.poll(); selected.add(candidate) }
        }
    }
    return EvidenceSearch(manifest.source, manifest.query, filter, manifest.executionComplete, manifest.lastFailure,
        manifest.textIncluded, includeText, manifest.upstreamCoverage, manifest.sampling,
        EvidenceSummary(captured, errors, unavailable, unknownNames, matched, matchedErrors, elapsedTotal, maxElapsed),
        selected.sortedWith(order), matched > selected.size)
}
