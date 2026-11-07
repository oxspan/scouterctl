package dev.scouterctl.core

enum class ServiceSort(val option: String) {
    ERRORS("errors"),
    ELAPSED("elapsed"),
    REQUESTS("requests");

    companion object {
        fun parse(value: String): ServiceSort = entries.singleOrNull { it.option == value }
            ?: throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Use --sort errors, elapsed or requests")
    }
}

data class RepresentativeRequest(val txid: String, val endTime: Long, val elapsedMs: Int, val profileStatus: ProfileAvailability)

data class ServiceAggregate(
    val serviceHash: Int, val serviceName: String?, val nameConflict: Boolean,
    val requests: Int, val errors: Int, val errorRate: Double,
    val elapsedTotalMs: Long, val averageElapsedMs: Double, val maxElapsedMs: Int,
    val unavailableProfiles: Int, val unknownServiceNames: Int,
    val slowest: RepresentativeRequest, val slowestError: RepresentativeRequest?,
)

data class ServiceSummary(
    val source: ServerInfo, val query: Query,
    val captureExecutionComplete: Boolean, val lastFailure: ErrorCode?,
    val textStored: Boolean, val textIncluded: Boolean,
    val upstreamCoverage: String, val sampling: String,
    val capturedRecords: Int, val capturedErrors: Int, val unavailableProfiles: Int, val unknownServiceNames: Int,
    val totalServices: Int, val limit: Int, val sort: ServiceSort,
    val services: List<ServiceAggregate>, val truncated: Boolean,
    val basis: String = "captured-xlogs",
) {
    val complete: Boolean get() = captureExecutionComplete && lastFailure == null && unavailableProfiles == 0
}

data class ServiceSummaryOptions(val limit: Int = 20, val sort: ServiceSort = ServiceSort.ERRORS, val includeText: Boolean = false)
