package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.adapters.serialization.json
import dev.scouterctl.application.CaptureOptions
import dev.scouterctl.application.captureEvidence
import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path

@Serializable
private data class ContinuationSample(
    val mode: String, val totalPages: Int, val recordsPerPage: Int, val invocations: Int,
    val existingReadBytes: Long, val elapsedNanos: Long, val profileRequests: Int,
    val heapPoolPeaks: Map<String, Long>, val heapUsedAfterBytes: Long,
)

@Serializable
private data class ContinuationReport(
    val javaVersion: String, val os: String, val maximumHeapBytes: Long,
    val implementationSha256: String, val samples: List<ContinuationSample>,
)

fun main(args: Array<String>) {
    require(args.size == 1) { "Use a new result directory" }
    val root = Files.createDirectory(Path.of(args.single()).toAbsolutePath())
    val samples = mutableListOf<ContinuationSample>()
    for (pages in listOf(120, 400)) for (continued in listOf(false, true)) {
        val directory = root.resolve("pages-$pages-" + if (continued) "continued" else "separate")
        val store = EvidenceStore(directory)
        val pageSize = 100
        var profiles = 0
        val query = portQuery.copy(to = portQuery.from + 3_600_000, pageSize = pageSize)
        val source = MemorySource({ cursor ->
            val first = cursor.txid.toLong() + 1
            val last = first + pageSize - 1
            XLogPage((first..last).map { portRow(it) }, Cursor(query.from + last, last.toString()), last < pages.toLong() * pageSize)
        }, { id ->
            profiles++
            ProfileRecord(id.toString(), List(20) { order -> ProfileStep("sql", order, SqlProfile(123, 1, 0)) })
        })
        var manifest = captureEvidence(source, query, CaptureOptions(false, 40, "memory"), store)
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        pools.forEach { it.resetPeakUsage() }
        var reads = 0L
        var elapsed = 0L
        var invocations = 0
        while (!manifest.executionComplete) {
            val measured = measureResumeReads(directory) {
                captureEvidence(source, query, CaptureOptions(true, 40, "memory",
                    maxTotalPages = if (continued) pages - 40 else null), store)
            }
            manifest = measured.result
            reads += measured.bytes.values.sum()
            elapsed += measured.elapsedNanos
            invocations++
            Files.move(directory.resolve("reads.jfr"), root.resolve("reads-$pages-$continued-$invocations.jfr"))
        }
        check(manifest.pages.size == pages && profiles == pages * pageSize)
        val peakPools = pools.associate { it.name to it.peakUsage.used }
        samples += ContinuationSample(if (continued) "continued" else "separate", pages, pageSize, invocations,
            reads, elapsed, profiles, peakPools, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
        store.validate(manifest)
        println("pages=$pages continued=$continued: invocations=$invocations reads=$reads elapsedNanos=$elapsed")
    }
    for (pair in samples.chunked(2)) {
        check(pair[0].existingReadBytes > pair[1].existingReadBytes)
        check(pair[1].invocations == 1)
    }
    val implementation = checkNotNull(EvidenceStore::class.java.getResourceAsStream("/dev/scouterctl/application/EvidenceCaptureKt.class"))
        .use { sha256(it.readAllBytes()) }
    Files.writeString(root.resolve("report.json"), json.encodeToString(ContinuationReport(
        System.getProperty("java.runtime.version"), System.getProperty("os.name"), Runtime.getRuntime().maxMemory(), implementation, samples)))
    println("Recorded continuation reads, elapsed time and heap pool peaks in ${root.resolve("report.json")}")
}
