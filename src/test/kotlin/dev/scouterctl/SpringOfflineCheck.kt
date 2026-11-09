package dev.scouterctl

import dev.scouterctl.application.*
import dev.scouterctl.adapters.files.*
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

internal class SpringOfflineCheck(private val work: Path) {
    private val directory = work.resolve("offline-capture")

    fun capture(cli: AgentCli, window: List<String>) {
        val args = listOf("capture", "--include-profiles", "--include-text", "--directory", directory.toString()) + window
        cli.run("capture-partial", args + listOf("--max-pages", "1"), 8)
        cli.run("capture-resumed", args + "--resume")
    }

    fun verify() {
        val store = EvidenceStore(directory)
        val manifest = store.load()
        check(manifest.complete && manifest.pages.sumOf { it.records } == 21)
        val rows = manifest.pages.flatMapIndexed { index, _ -> store.readPage(manifest, index) }
        check(rows.map { it.xlog.txid }.toSet().size == 21)
        rows.forEach { saved ->
            val txid = saved.xlog.txid
            val expected = json.parseToJsonElement(Files.readString(work.resolve("text-profile-$txid.jsonl"))).jsonObject["data"]!!
            val args = listOf("inspect", "--directory", directory.toString(), "--txid", txid)
            val resolved = runOfflineCli(work, args + "--include-text")
            check(resolved.exit == 0 && resolved.stderr.isEmpty())
            val inspection = json.decodeFromJsonElement<InspectionDto>(resolved.data()).toModel()
            check(inspection.record.profile == decodeLiveProfile(expected))
            check(inspection.record.xlog == saved.xlog)
            val structural = runOfflineCli(work, args)
            check(structural.exit == 0 && structural.stderr.isEmpty())
            check(!structural.output.contains("private-bind-marker") && !structural.output.contains("private-application-error-marker"))
        }
        Files.writeString(work.resolve("offline-summary.json"), buildJsonObject {
            put("inspectedTransactions", 21); put("textInspections", 21); put("structuralInspections", 21)
            put("collectorStoppedBeforeInspect", true); put("credentialsProvided", false)
            put("resumedCapture", true); put("matchesOnlineProfiles", true)
        }.toString())
        SpringSearchCheck(work, directory).verify()
        SpringSummaryCheck(work, directory).verify()
        println("PASS: 21 captured profiles match online data after Collector shutdown; 42 offline inspections without credentials")
    }
}
