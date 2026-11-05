package dev.scouterctl.core

enum class ComparisonWarning {
    TRAFFIC_COVERAGE_UNVERIFIED,
    DIFFERENT_SERVER,
    DIFFERENT_COLLECTOR_VERSION,
    DIFFERENT_OBJECT,
    DIFFERENT_TIMEZONE,
    DIFFERENT_DURATION,
    OVERLAPPING_WINDOWS,
    INCOMPLETE_CAPTURE,
    UNAVAILABLE_PROFILES,
    UNRESOLVED_SERVICE_NAMES,
    SERVICE_IDENTITY_CONFLICT,
}

enum class ServicePresence {
    BOTH,
    BEFORE_ONLY,
    AFTER_ONLY,
}

data class ComparisonCapture(
    val source: ServerInfo, val query: Query, val durationMs: Long,
    val captureExecutionComplete: Boolean, val lastFailure: ErrorCode?, val textStored: Boolean,
    val upstreamCoverage: String, val sampling: String,
    val capturedRecords: Int, val capturedErrors: Int, val unavailableProfiles: Int,
    val unknownServiceNames: Int, val totalServices: Int,
)

data class ServiceDelta(
    val requests: Int, val errors: Int, val errorRatePercentagePoints: Double,
    val elapsedTotalMs: Long, val averageElapsedMs: Double, val maxElapsedMs: Long,
)

data class ComparedService(
    val serviceHash: Int, val presence: ServicePresence, val identityConflict: Boolean,
    val before: ServiceAggregate?, val after: ServiceAggregate?, val delta: ServiceDelta?,
)

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

data class ComparisonOptions(val limit: Int = 20, val includeText: Boolean = false)
