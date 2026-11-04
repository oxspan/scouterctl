package dev.scouterctl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
enum class ComparisonWarning {
    @SerialName("traffic-coverage-unverified") TRAFFIC_COVERAGE_UNVERIFIED,
    @SerialName("different-server") DIFFERENT_SERVER,
    @SerialName("different-collector-version") DIFFERENT_COLLECTOR_VERSION,
    @SerialName("different-object") DIFFERENT_OBJECT,
    @SerialName("different-timezone") DIFFERENT_TIMEZONE,
    @SerialName("different-duration") DIFFERENT_DURATION,
    @SerialName("overlapping-windows") OVERLAPPING_WINDOWS,
    @SerialName("incomplete-capture") INCOMPLETE_CAPTURE,
    @SerialName("unavailable-profiles") UNAVAILABLE_PROFILES,
    @SerialName("unresolved-service-names") UNRESOLVED_SERVICE_NAMES,
    @SerialName("service-identity-conflict") SERVICE_IDENTITY_CONFLICT,
}

@Serializable
enum class ServicePresence {
    @SerialName("both") BOTH,
    @SerialName("before-only") BEFORE_ONLY,
    @SerialName("after-only") AFTER_ONLY,
}

data class ComparisonOptions(val limit: Int = 20, val includeText: Boolean = false)

@Serializable
data class ComparisonCapture(
    val source: ServerInfo, val query: Query, val durationMs: Long,
    val captureExecutionComplete: Boolean, val lastFailure: ErrorCode?, val textStored: Boolean,
    val upstreamCoverage: String, val sampling: String,
    val capturedRecords: Int, val capturedErrors: Int, val unavailableProfiles: Int,
    val unknownServiceNames: Int, val totalServices: Int,
)

@Serializable
data class ServiceDelta(
    val requests: Int, val errors: Int, val errorRatePercentagePoints: Double,
    val elapsedTotalMs: Long, val averageElapsedMs: Double, val maxElapsedMs: Long,
)

@Serializable
data class ComparedService(
    val serviceHash: Int, val presence: ServicePresence, val identityConflict: Boolean,
    val before: ServiceAggregate?, val after: ServiceAggregate?, val delta: ServiceDelta?,
)

@Serializable
data class CaptureComparison(
    val before: ComparisonCapture, val after: ComparisonCapture,
    val warnings: List<ComparisonWarning>, val textIncluded: Boolean,
    val totalServices: Int, val limit: Int, val truncated: Boolean,
    val services: List<ComparedService>,
    val basis: String = "captured-xlogs", val matching: String = "service-hash",
    val order: String = "service-hash-asc", val deltaDirection: String = "after-minus-before",
) {
    val complete: Boolean get() = listOf(before, after).all {
        it.captureExecutionComplete && it.lastFailure == null && it.unavailableProfiles == 0
    }
}

fun compareCaptures(before: Path, after: Path, options: ComparisonOptions): CaptureComparison {
    if (options.limit !in 1..1000) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Use --limit 1..1000")
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

private fun comparisonSummary(directory: Path, includeText: Boolean): ServiceSummary {
    val manifest = EvidenceStore(directory).load()
    if (includeText && !manifest.textIncluded) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Text was not stored in both captures")
    // Resolve stored names even for structural output so conflicting hash identities never receive a delta.
    val summary = summarizeCapture(directory, ServiceSummaryOptions(limit = 1000, includeText = manifest.textIncluded))
    if (summary.truncated) throw CliFailure(ErrorCode.PARTIAL_RESULT, "Comparison exceeds 1000 services per capture; use smaller captures")
    return summary
}

private fun ServiceAggregate.visible(includeText: Boolean) = if (includeText) this else copy(serviceName = null)

private fun ServiceSummary.snapshot() = ComparisonCapture(source, query, query.to - query.from,
    captureExecutionComplete, lastFailure, textStored, upstreamCoverage, sampling,
    capturedRecords, capturedErrors, unavailableProfiles, unknownServiceNames, totalServices)
