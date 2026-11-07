package dev.scouterctl.application

import dev.scouterctl.core.*

fun compareCaptures(before: EvidenceRepository, after: EvidenceRepository, options: ComparisonOptions): CaptureComparison {
    if (options.limit !in 1..1000) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Use --limit 1..1000")
    val baseline = comparisonSummary(before, options.includeText)
    val candidate = comparisonSummary(after, options.includeText)
    val beforeByHash = baseline.services.associateBy { it.serviceHash }
    val afterByHash = candidate.services.associateBy { it.serviceHash }
    val services = (beforeByHash.keys + afterByHash.keys).sorted().map { hash ->
        val left = beforeByHash[hash]
        val right = afterByHash[hash]
        val conflict = left?.nameConflict == true || right?.nameConflict == true ||
            (left?.serviceName != null && right?.serviceName != null && left.serviceName != right.serviceName)
        val presence = when {
            left == null -> ServicePresence.AFTER_ONLY
            right == null -> ServicePresence.BEFORE_ONLY
            else -> ServicePresence.BOTH
        }
        val delta = if (left == null || right == null || conflict) null else ServiceDelta(
            right.requests - left.requests, right.errors - left.errors,
            (right.errorRate - left.errorRate) * 100.0,
            right.elapsedTotalMs - left.elapsedTotalMs, right.averageElapsedMs - left.averageElapsedMs,
            right.maxElapsedMs.toLong() - left.maxElapsedMs.toLong(),
        )
        ComparedService(hash, presence, conflict,
            left?.visible(options.includeText), right?.visible(options.includeText), delta)
    }
    val left = baseline.snapshot()
    val right = candidate.snapshot()
    val warnings = buildList {
        add(ComparisonWarning.TRAFFIC_COVERAGE_UNVERIFIED)
        if (left.source.serverId != right.source.serverId) add(ComparisonWarning.DIFFERENT_SERVER)
        if (left.source.version != right.source.version) add(ComparisonWarning.DIFFERENT_COLLECTOR_VERSION)
        if (left.query.objHash != right.query.objHash) add(ComparisonWarning.DIFFERENT_OBJECT)
        if (left.query.timezone != right.query.timezone) add(ComparisonWarning.DIFFERENT_TIMEZONE)
        if (left.durationMs != right.durationMs) add(ComparisonWarning.DIFFERENT_DURATION)
        if (left.query.from < right.query.to && right.query.from < left.query.to) add(ComparisonWarning.OVERLAPPING_WINDOWS)
        if (listOf(left, right).any { !it.captureExecutionComplete || it.lastFailure != null }) add(ComparisonWarning.INCOMPLETE_CAPTURE)
        if (left.unavailableProfiles > 0 || right.unavailableProfiles > 0) add(ComparisonWarning.UNAVAILABLE_PROFILES)
        if (left.unknownServiceNames > 0 || right.unknownServiceNames > 0) add(ComparisonWarning.UNRESOLVED_SERVICE_NAMES)
        if (services.any { it.identityConflict }) add(ComparisonWarning.SERVICE_IDENTITY_CONFLICT)
    }
    return CaptureComparison(left, right, warnings, options.includeText,
        services.size, options.limit, services.size > options.limit, services.take(options.limit))
}

private fun comparisonSummary(store: EvidenceRepository, includeText: Boolean): ServiceSummary {
    val manifest = store.load()
    if (includeText && !manifest.textIncluded) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Text was not stored in both captures")
    // Resolve stored names even for structural output so conflicting hash identities never receive a delta.
    val summary = summarizeCapture(store, ServiceSummaryOptions(limit = 1000, includeText = manifest.textIncluded))
    if (summary.truncated) throw OperationFailure(ErrorCode.PARTIAL_RESULT, "Comparison exceeds 1000 services per capture; use smaller captures")
    return summary
}

private fun ServiceAggregate.visible(includeText: Boolean) = if (includeText) this else copy(serviceName = null)

private fun ServiceSummary.snapshot() = ComparisonCapture(source, query, query.to - query.from,
    captureExecutionComplete, lastFailure, textStored, upstreamCoverage, sampling,
    capturedRecords, capturedErrors, unavailableProfiles, unknownServiceNames, totalServices)
