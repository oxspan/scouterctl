package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.adapters.serialization.json
import dev.scouterctl.application.CaptureOptions
import dev.scouterctl.application.captureEvidence
import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
private data class ResumeReadSample(
    val pageSize: Int, val invocation: Int, val existingPages: Int, val existingRecords: Int,
    val existingPageBytes: Long, val existingReadBytes: Long, val scanCounts: Map<String, Int>,
    val preparationNanos: Long?, val captureNanos: Long, val addedPages: Int, val profileRequests: Int,
    val executionComplete: Boolean,
)

@Serializable
private data class ResumeReadReport(
    val os: String, val architecture: String, val javaVersion: String, val processors: Int,
    val maximumHeapBytes: Long, val classSha256: Map<String, String>, val samples: List<ResumeReadSample>,
)

fun main(args: Array<String>) {
    require(args.size == 1) { "Use a new result directory" }
    val root = Files.createDirectory(Path.of(args.single()).toAbsolutePath())
    val samples = mutableListOf<ResumeReadSample>()
    for (pageSize in listOf(2, 100)) {
        val directory = root.resolve("records-per-page-$pageSize")
        val fixture = ResumeBenchmarkFixture(pageSize)
        val store = EvidenceStore(directory)
        var previous: EvidenceManifest? = null
        repeat(4) { index ->
            val pages = previous?.pages.orEmpty()
            val sizes = pages.associate { directory.resolve(it.name) to Files.size(directory.resolve(it.name)) }
            val profilesBefore = fixture.profileRequests
            fixture.firstPageNanos = null
            var started = 0L
            val measurement = measureResumeReads(directory) {
                started = System.nanoTime()
                captureEvidence(fixture.source, fixture.query, CaptureOptions(index > 0, 40, "memory"), store)
            }
            val result = measurement.result
            val reads = sizes.map { (file, size) ->
                val bytes = measurement.bytes[file] ?: 0L
                check(bytes > 0 && bytes % size == 0L) { "Incomplete file-read measurement for $file" }
                (bytes / size).toString()
            }.groupingBy { it }.eachCount()
            val added = result.pages.size - pages.size
            val profiles = fixture.profileRequests - profilesBefore
            check(profiles == added * pageSize) { "Committed profiles were fetched again" }
            check(result.pages.size == minOf((index + 1) * 40, 120))
            check(result.pages.sumOf { it.records } == result.pages.size * pageSize)
            check(result.executionComplete == (index >= 2))
            samples += ResumeReadSample(pageSize, index + 1, pages.size, pages.sumOf { it.records },
                sizes.values.sum(), sizes.keys.sumOf { measurement.bytes[it] ?: 0 }, reads,
                fixture.firstPageNanos?.minus(started), measurement.elapsedNanos, added, profiles, result.executionComplete)
            Files.move(directory.resolve("reads.jfr"), root.resolve("reads-$pageSize-${index + 1}.jfr"))
            previous = result
        }
        check(fixture.profileRequests == 120 * pageSize)
        store.validate(checkNotNull(previous))
    }
    val classes = listOf("application/EvidenceCaptureKt", "adapters/files/EvidenceStore").associateWith { name ->
        val bytes = checkNotNull(EvidenceStore::class.java.getResourceAsStream("/dev/scouterctl/$name.class"))
            .use { it.readAllBytes() }
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    val report = ResumeReadReport(System.getProperty("os.name") + " " + System.getProperty("os.version"),
        System.getProperty("os.arch"), System.getProperty("java.runtime.version"), Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().maxMemory(), classes, samples)
    Files.writeString(root.resolve("report.json"), json.encodeToString(report))
    println("Recorded ${samples.size} capture invocations in ${root.resolve("report.json")}")
}

private class ResumeBenchmarkFixture(pageSize: Int) {
    val query = portQuery.copy(to = portQuery.from + 3_600_000, pageSize = pageSize)
    var profileRequests = 0
    var firstPageNanos: Long? = null
    val source = MemorySource({ cursor ->
        if (firstPageNanos == null) firstPageNanos = System.nanoTime()
        val first = cursor.txid.toLong() + 1
        val last = first + pageSize - 1
        XLogPage((first..last).map { portRow(it) }, Cursor(query.from + last, last.toString()), last < 120L * pageSize)
    }, { id ->
        profileRequests++
        ProfileRecord(id.toString(), List(20) { order -> ProfileStep("sql", order, SqlProfile(123, 1, 0)) })
    })
}
