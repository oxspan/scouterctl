package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.application.*
import dev.scouterctl.adapters.serialization.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class SerializationContractTest : FunSpec({
    test("dictionary status is a stored field while computed completeness is absent") {
        json.encodeToString(DictionaryText(7).toDto()) shouldBe """{"hash":7,"text":null,"status":"unresolved"}"""
        json.encodeToString(DictionaryText(7, "SELECT 1").toDto()) shouldBe """{"hash":7,"text":"SELECT 1","status":"resolved"}"""
        val source = ServerInfo("1", "2.21.3", "UTC", "test")
        val query = Query("20260101", 1767225600000, 1767225601000, "UTC", 1, 25)
        json.encodeToString(EvidenceManifest(source = source, endpoint = "localhost:6100", query = query, textIncluded = false).toDto()) shouldBe
            """{"schemaVersion":"scouterctl.capture/v2","source":{"serverId":"1","version":"2.21.3","timezoneDisplayName":"UTC","group":"test","source":"collector-tcp","effectivePermission":"unknown","nativeTls":false},"endpoint":"localhost:6100","query":{"date":"20260101","from":1767225600000,"to":1767225601000,"timezone":"UTC","objHash":1,"pageSize":25},"textIncluded":false,"cursor":{"bucketTime":0,"txid":"0"},"executionComplete":false,"pages":[],"lastFailure":null,"upstreamCoverage":"unknown","sampling":"unknown","timezoneVerification":"operator-declared"}"""
    }
    test("v1 defaults and signed transaction identifiers retain their wire bytes") {
        json.encodeToString(XLogRecord("-9223372036854775808", "9223372036854775807", "0", 1, 2, 3, 4, 0).toDto()) shouldBe
            """{"txid":"-9223372036854775808","gxid":"9223372036854775807","caller":"0","endTime":1,"objHash":2,"serviceHash":3,"elapsed":4,"errorHash":0}"""
        json.encodeToString(ProfileRecord("1", listOf(ProfileStep("SQL", 0, SqlProfile(1, 2, 0)))).toDto()) shouldBe
            """{"txid":"1","steps":[{"type":"SQL","order":0,"sql":{"hash":1,"elapsedMs":2,"errorHash":0,"updated":null,"statement":null,"error":null}}],"detail":"structural-only; free text and binds omitted","transaction":null}"""
        json.encodeToString(ContextConfig().toDto()) shouldBe """{"schemaVersion":"scouterctl.contexts/v1","current":null,"contexts":{}}"""
    }
    test("decoded dictionary status survives boundaries without changing data class equality or copy") {
        val wire = """{"hash":7,"text":null,"status":"legacy-value"}"""
        val model = json.decodeFromString<DictionaryTextDto>(wire).toModel()
        model.status shouldBe "legacy-value"
        model shouldBe DictionaryText(7)
        model.copy().status shouldBe "unresolved"
        json.encodeToString(model.toDto()) shouldBe wire
    }
    test("legacy v1 manifest and v2 page retain their exact fields and defaults") {
        val source = ServerInfo("1", "2.21.3", "UTC", "test")
        val query = Query("20260101", 1767225600000, 1767225601000, "UTC", 1, 25)
        val v1 = """{"schemaVersion":"scouterctl.capture/v1","source":{"serverId":"1","version":"2.21.3","timezoneDisplayName":"UTC","group":"test","source":"collector-tcp","effectivePermission":"unknown","nativeTls":false},"endpoint":"localhost:6100","query":{"date":"20260101","from":1767225600000,"to":1767225601000,"timezone":"UTC","objHash":1,"pageSize":25},"cursor":{"bucketTime":0,"txid":"0"},"executionComplete":false,"upstreamCoverage":"unknown","sampling":"unknown","timezoneVerification":"operator-declared","files":[]}"""
        json.encodeToString(CaptureManifest(source = source, endpoint = "localhost:6100", query = query).toDto()) shouldBe v1
        json.decodeFromString<CaptureManifestDto>(v1).toModel().files shouldBe emptyList()
        val page = """{"xlog":{"txid":"1","gxid":"0","caller":"0","endTime":1,"objHash":2,"serviceHash":3,"elapsed":4,"errorHash":0},"profileStatus":"unavailable","profile":null}"""
        val record = EvidenceRecord(XLogRecord("1", "0", "0", 1, 2, 3, 4, 0), ProfileAvailability.UNAVAILABLE)
        json.encodeToString(record.toDto()) shouldBe page
        json.decodeFromString<EvidenceRecordDto>(page).toModel() shouldBe record
    }
    test("wire enum labels remain stable") {
        json.encodeToString(ProfileAvailability.entries.map { it.toDto() }) shouldBe """["available","unavailable"]"""
        json.encodeToString(ServiceSort.entries.map { it.toDto() }) shouldBe """["errors","elapsed","requests"]"""
        json.encodeToString(ServicePresence.entries.map { it.toDto() }) shouldBe """["both","before-only","after-only"]"""
        json.encodeToString(ComparisonWarning.entries.map { it.toDto() }) shouldBe """["traffic-coverage-unverified","different-server","different-collector-version","different-object","different-timezone","different-duration","overlapping-windows","incomplete-capture","unavailable-profiles","unresolved-service-names","service-identity-conflict"]"""
    }
})
