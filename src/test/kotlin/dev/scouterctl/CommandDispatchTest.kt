package dev.scouterctl

import dev.scouterctl.application.*
import dev.scouterctl.cli.*
import dev.scouterctl.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import dev.scouterctl.adapters.serialization.json
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.IOException
import java.nio.file.Path
import picocli.CommandLine

class CommandDispatchTest : FunSpec({
    test("offline interpretation and execution never open configuration credentials or collector") {
        val store = MemoryEvidence()
        store.save(EvidenceManifest(source = portSource, endpoint = "memory", query = portQuery, textIncluded = false, executionComplete = true))
        val bytes = ByteArrayOutputStream()
        val output = JsonOutput(PrintStream(bytes, true, Charsets.UTF_8))
        val contexts: () -> ContextRepository = { error("Offline command opened config") }
        val dispatcher = dispatchForTest(contexts, { store }, { _, _ -> error("Offline command opened collector") },
            PasswordInput { _, _, _ -> error("Offline command requested password") }, output)
        CommandLine(Cli(dispatcher, output)).execute("inspect", "search", "--directory", "unused", "--limit", "1") shouldBe 0
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["data"]!!.jsonObject["records"]!!.jsonArray.size shouldBe 0
    }
    test("context commands persist through their port without opening the collector") {
        val store = MemoryContexts()
        val output = JsonOutput(PrintStream(ByteArrayOutputStream()))
        val dispatcher = dispatchForTest({ store }, { error("Context opened capture") },
            { _, _ -> error("Context opened collector") }, PasswordInput { _, _, _ -> error("Context requested password") }, output)
        val command = CommandLine(Cli(dispatcher, output))
        command.execute("context", "add", "--name", "local", "--user", "reader") shouldBe 0
        store.config.contexts["local"] shouldBe SavedConnection("127.0.0.1", 6100, "reader")
    }
    test("failed stream output closes the source and stops before another page") {
        var pages = 0
        val source = MemorySource({ pages++; XLogPage(listOf(portRow(1)), Cursor(portQuery.from + 1, "1"), true) })
        var lines = 0
        val output = JsonOutput(PrintStream(object : OutputStream() {
            override fun write(value: Int) {
                if (lines >= 1) throw IOException("closed output")
                if (value == 10) lines++
            }
        }, true, Charsets.UTF_8))
        val dispatcher = dispatchForTest({ error("Explicit connection opened config") }, { error("Search opened capture") },
            { _, _ -> source }, PasswordInput { _, _, _ -> "test" }, output)
        val connection = ConnectionOptions().apply { host = "localhost"; port = 6100; user = "reader" }
        output.run { dispatcher.execute(OnlineRequest.Search(portQuery, 2), connection) } shouldBe 10
        pages shouldBe 1
        source.closed shouldBe true
    }
})

private fun dispatchForTest(contexts: () -> ContextRepository, evidence: (Path) -> EvidenceRepository,
    source: (Endpoint, String) -> ObservationSource, password: PasswordInput, output: JsonOutput): CommandDispatcher =
    CommandDispatcher(ContextCommands(contexts, output), OfflineCommands(evidence, output),
        OnlineCommands(contexts, source, { error("Unexpected v1 capture") }, evidence, password, output))
