package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*
import dev.scouterctl.adapters.serialization.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CaptureComparisonTest : FunSpec({
    test("offline comparison reports after-minus-before metrics and one-sided observations without changing captures") {
        val work = tempdir().toPath()
        val before = comparisonFixture(work, true)
        val after = comparisonFixture(work, false)
        val original = fingerprints(listOf(before, after))
        Files.writeString(work.resolve("config.json"), "invalid online config")
        val result = compareCli(work)
        result.exit shouldBe 0
        result.stderr shouldBe ""
        val comparison = json.decodeFromJsonElement<CaptureComparisonDto>(result.data()).toModel()
        comparison.basis shouldBe "captured-xlogs"
        comparison.before.capturedRecords shouldBe 3
        comparison.after.capturedRecords shouldBe 4
        comparison.before.durationMs shouldBe 60000
        comparison.after.query.from shouldBe 1793491260000L
        comparison.warnings shouldBe listOf(ComparisonWarning.TRAFFIC_COVERAGE_UNVERIFIED)
        comparison.totalServices shouldBe 3
        comparison.services.map { it.serviceHash } shouldBe listOf(10, 20, 30)
        val both = comparison.services.first()
        both.presence shouldBe ServicePresence.BOTH
        both.identityConflict shouldBe false
        val left = checkNotNull(both.before)
        val right = checkNotNull(both.after)
        val delta = checkNotNull(both.delta)
        left.requests shouldBe 2
        right.requests shouldBe 3
        left.errorRate shouldBe 0.5
        right.errorRate shouldBe (2.0 / 3)
        delta.requests shouldBe 1
        delta.errors shouldBe 1
        delta.errorRatePercentagePoints shouldBe (100.0 / 6 plusOrMinus 0.000001)
        delta.elapsedTotalMs shouldBe 800L
        delta.averageElapsedMs shouldBe 200.0
        delta.maxElapsedMs shouldBe 300L
        right.slowest.txid shouldBe "9223372036854775805"
        comparison.services[1].presence shouldBe ServicePresence.BEFORE_ONLY
        comparison.services[1].after shouldBe null
        comparison.services[1].delta shouldBe null
        comparison.services[2].presence shouldBe ServicePresence.AFTER_ONLY
        comparison.services[2].before shouldBe null
        comparison.services[2].delta shouldBe null
        comparison.services.all { it.before?.serviceName == null && it.after?.serviceName == null } shouldBe true
        result.output shouldNotContain "checkout"
        fingerprints(listOf(before, after)) shouldBe original
    }

    test("same capture yields zero deltas and overlap warning while output limit preserves full totals") {
        val work = tempdir().toPath()
        val directory = comparisonFixture(work, true)
        val result = ContractCli(work).run(listOf("inspect", "compare", "--before", directory.toString(),
            "--after", directory.toString(), "--limit", "1", "--include-text"), null)
        result.exit shouldBe 0
        val comparison = json.decodeFromJsonElement<CaptureComparisonDto>(result.data()).toModel()
        comparison.totalServices shouldBe 2
        comparison.before.capturedRecords shouldBe 3
        comparison.after.capturedRecords shouldBe 3
        comparison.truncated shouldBe true
        comparison.services.size shouldBe 1
        comparison.services.single().delta shouldBe ServiceDelta(0, 0, 0.0, 0, 0.0, 0)
        comparison.services.single().before!!.serviceName shouldBe "/checkout-한글-café"
        comparison.warnings shouldBe listOf(ComparisonWarning.TRAFFIC_COVERAGE_UNVERIFIED, ComparisonWarning.OVERLAPPING_WINDOWS)
    }

    test("scope differences and partial captures remain explicit with partial exit") {
        val work = tempdir().toPath()
        comparisonFixture(work, true)
        val after = comparisonFixture(work, false)
        replaceComparisonRows(after, comparisonRows(after).map { it.copy(xlog = it.xlog.copy(objHash = 99)) })
        val store = EvidenceStore(after)
        val m = store.load()
        store.save(m.copy(source = m.source.copy(serverId = "other", version = "2.22"),
            query = m.query.copy(objHash = 99, timezone = "Etc/UTC", to = m.query.to + 60000),
            executionComplete = false, lastFailure = ErrorCode.CONNECTION_FAILED))
        val result = compareCli(work)
        result.exit shouldBe 8
        result.events.single()["executionComplete"]!!.jsonPrimitive.boolean shouldBe false
        val comparison = json.decodeFromJsonElement<CaptureComparisonDto>(result.data()).toModel()
        comparison.after.lastFailure shouldBe ErrorCode.CONNECTION_FAILED
        comparison.after.durationMs shouldBe 120000
        comparison.after.sampling shouldBe "unknown"
        comparison.warnings shouldBe listOf(ComparisonWarning.TRAFFIC_COVERAGE_UNVERIFIED,
            ComparisonWarning.DIFFERENT_SERVER, ComparisonWarning.DIFFERENT_COLLECTOR_VERSION,
            ComparisonWarning.DIFFERENT_OBJECT, ComparisonWarning.DIFFERENT_TIMEZONE,
            ComparisonWarning.DIFFERENT_DURATION, ComparisonWarning.INCOMPLETE_CAPTURE)
    }

    test("missing profiles preserve observed XLog metrics and expose unavailable counts") {
        val work = tempdir().toPath()
        comparisonFixture(work, true)
        val after = comparisonFixture(work, false)
        replaceComparisonRows(after, comparisonRows(after).map {
            if (it.xlog.elapsed == 600) it.copy(profileStatus = ProfileAvailability.UNAVAILABLE, profile = null) else it
        })
        val result = compareCli(work)
        result.exit shouldBe 8
        val comparison = json.decodeFromJsonElement<CaptureComparisonDto>(result.data()).toModel()
        comparison.after.unavailableProfiles shouldBe 1
        comparison.after.unknownServiceNames shouldBe 1
        comparison.services.first().delta!!.averageElapsedMs shouldBe 200.0
        comparison.warnings shouldBe listOf(ComparisonWarning.TRAFFIC_COVERAGE_UNVERIFIED,
            ComparisonWarning.UNAVAILABLE_PROFILES, ComparisonWarning.UNRESOLVED_SERVICE_NAMES)
    }

    listOf(false, true).forEach { withinCapture ->
        test("conflicting service identities suppress deltas even without text output withinCapture=$withinCapture") {
            val work = tempdir().toPath()
            comparisonFixture(work, true)
            val after = comparisonFixture(work, false)
            replaceComparisonRows(after, comparisonRows(after).mapIndexed { index, row ->
                if (row.xlog.serviceHash == 10 && (!withinCapture || index == 0)) row.copy(profile = row.profile!!.copy(
                    transaction = ProfileTransaction("available", DictionaryText(10, "/private-different-name")))) else row
            })
            val result = compareCli(work)
            result.exit shouldBe 0
            val comparison = json.decodeFromJsonElement<CaptureComparisonDto>(result.data()).toModel()
            comparison.services.first().identityConflict shouldBe true
            comparison.services.first().delta shouldBe null
            comparison.warnings.contains(ComparisonWarning.SERVICE_IDENTITY_CONFLICT) shouldBe true
            result.output shouldNotContain "private-different-name"
            result.output shouldNotContain "checkout"
        }
    }

    test("empty captures do not invent zero-valued services or undefined ratios") {
        val work = tempdir().toPath()
        val before = comparisonFixture(work, true)
        val after = comparisonFixture(work, false)
        replaceComparisonRows(after, emptyList())
        val oneEmpty = json.decodeFromJsonElement<CaptureComparisonDto>(compareCli(work).data()).toModel()
        oneEmpty.after.capturedRecords shouldBe 0
        oneEmpty.services.all { it.presence == ServicePresence.BEFORE_ONLY && it.delta == null } shouldBe true
        replaceComparisonRows(before, emptyList())
        val empty = compareCli(work)
        empty.exit shouldBe 0
        empty.data()["services"]!!.jsonArray.size shouldBe 0
        empty.data()["totalServices"]!!.jsonPrimitive.int shouldBe 0
        empty.output shouldNotContain "NaN"
    }

    test("structural captures work without names and text opt-in requires text on both sides") {
        val work = tempdir().toPath()
        comparisonFixture(work, true)
        val after = comparisonFixture(work, false)
        replaceComparisonRows(after, comparisonRows(after).map { it.copy(profile = it.profile!!.copy(transaction = null)) })
        val store = EvidenceStore(after)
        store.save(store.load().copy(textIncluded = false))
        compareCli(work).exit shouldBe 0
        compareCli(work, listOf("--include-text")).exit shouldBe 2
        val comparison = json.decodeFromJsonElement<CaptureComparisonDto>(compareCli(work).data()).toModel()
        comparison.after.textStored shouldBe false
        comparison.after.unknownServiceNames shouldBe 4
        comparison.services.first().delta!!.requests shouldBe 1
    }
})

private fun fingerprints(directories: List<Path>): Map<String, List<Byte>> = directories.flatMap { directory ->
    Files.list(directory).use { paths -> paths.toList().map { file ->
        file.toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).toList()
    } }
}.toMap()
