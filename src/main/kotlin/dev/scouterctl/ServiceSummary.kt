package dev.scouterctl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
enum class ServiceSort(val option: String) {
    @SerialName("errors") ERRORS("errors"),
    @SerialName("elapsed") ELAPSED("elapsed"),
    @SerialName("requests") REQUESTS("requests");

    companion object {
        fun parse(value: String): ServiceSort = entries.singleOrNull { it.option == value }
            ?: throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Use --sort errors, elapsed or requests")
    }
}

data class ServiceSummaryOptions(val limit: Int = 20, val sort: ServiceSort = ServiceSort.ERRORS, val includeText: Boolean = false)

@Serializable
data class RepresentativeRequest(val txid: String, val endTime: Long, val elapsedMs: Int, val profileStatus: ProfileAvailability)

@Serializable
data class ServiceAggregate(
    val serviceHash: Int, val serviceName: String?, val nameConflict: Boolean,
    val requests: Int, val errors: Int, val errorRate: Double,
    val elapsedTotalMs: Long, val averageElapsedMs: Double, val maxElapsedMs: Int,
    val unavailableProfiles: Int, val unknownServiceNames: Int,
    val slowest: RepresentativeRequest, val slowestError: RepresentativeRequest?,
)

@Serializable
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

fun summarizeCapture(directory: Path, options: ServiceSummaryOptions): ServiceSummary {
    if (options.limit !in 1..1000) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Use --limit 1..1000")
    val store = EvidenceStore(directory)
    val manifest = store.load()
    if (options.includeText && !manifest.textIncluded) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Text was not stored in this capture")
    val groups = mutableMapOf<Int, ServiceAccumulator>()
    store.forEachRecord(manifest) { record ->
        if (record.xlog.elapsed < 0) throw CliFailure(ErrorCode.IO_ERROR, "Capture has a negative request duration")
        val hash = record.xlog.serviceHash
        val group = groups[hash] ?: run {
            if (groups.size >= 10000) throw CliFailure(ErrorCode.PARTIAL_RESULT, "Summary exceeds 10000 services; use a smaller capture")
            ServiceAccumulator(hash).also { groups[hash] = it }
        }
        group.add(record)
    }
    val all = groups.values.map { it.finish(options.includeText) }
    val order = when (options.sort) {
        ServiceSort.ERRORS -> compareByDescending<ServiceAggregate> { it.errors }.thenByDescending { it.maxElapsedMs }
        ServiceSort.ELAPSED -> compareByDescending<ServiceAggregate> { it.maxElapsedMs }.thenByDescending { it.errors }
        ServiceSort.REQUESTS -> compareByDescending<ServiceAggregate> { it.requests }.thenByDescending { it.errors }
    }.thenBy { it.serviceHash }
    return ServiceSummary(manifest.source, manifest.query, manifest.executionComplete, manifest.lastFailure,
        manifest.textIncluded, options.includeText, manifest.upstreamCoverage, manifest.sampling,
        all.sumOf { it.requests }, all.sumOf { it.errors }, all.sumOf { it.unavailableProfiles }, all.sumOf { it.unknownServiceNames },
        all.size, options.limit, options.sort, all.sortedWith(order).take(options.limit), all.size > options.limit)
}

private val representativeOrder = compareByDescending<RepresentativeRequest> { it.elapsedMs }
    .thenBy { it.endTime }.thenBy { it.txid.toLong() }

private class ServiceAccumulator(private val hash: Int) {
    private var requests = 0
    private var errors = 0
    private var elapsed = 0L
    private var unavailable = 0
    private var unknownNames = 0
    private var name: String? = null
    private var nameConflict = false
    private var slowest: RepresentativeRequest? = null
    private var slowestError: RepresentativeRequest? = null

    fun add(record: EvidenceRecord) {
        val row = record.xlog
        requests++
        elapsed += row.elapsed.toLong()
        if (row.errorHash != 0) errors++
        if (record.profileStatus == ProfileAvailability.UNAVAILABLE) unavailable++
        val resolvedName = record.profile?.transaction?.service?.takeIf { it.hash == hash }?.text
        if (resolvedName == null) unknownNames++
        else if (name == null) name = resolvedName
        else if (name != resolvedName) nameConflict = true
        val candidate = RepresentativeRequest(row.txid, row.endTime, row.elapsed, record.profileStatus)
        if (slowest?.let { representativeOrder.compare(candidate, it) < 0 } != false) slowest = candidate
        if (row.errorHash != 0 && slowestError?.let { representativeOrder.compare(candidate, it) < 0 } != false) slowestError = candidate
    }

    fun finish(includeText: Boolean): ServiceAggregate {
        val longest = checkNotNull(slowest)
        return ServiceAggregate(hash, if (includeText && !nameConflict) name else null, nameConflict,
            requests, errors, errors.toDouble() / requests, elapsed, elapsed.toDouble() / requests,
            longest.elapsedMs, unavailable, unknownNames, longest, slowestError)
    }
}
