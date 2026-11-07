package dev.scouterctl

import dev.scouterctl.adapters.serialization.*

import com.pty4j.PtyProcessBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class TerminalCli(work: Path, args: List<String>, password: String? = null, command: List<String>? = null) : AutoCloseable {
    private val environment = System.getenv().toMutableMap().apply {
        remove("SCOUTERCTL_PASSWORD")
        put("SCOUTERCTL_CONFIG", work.resolve("config.json").toString())
        put("TERM", "xterm")
        if (password != null) put("SCOUTERCTL_PASSWORD", password)
    }
    private val process = PtyProcessBuilder(((command ?: listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-jar", System.getProperty("cli.jar"),
    )) + args).toTypedArray())
        .setEnvironment(environment).setDirectory(work.toString())
        .setInitialColumns(2000).setInitialRows(50)
        .setUseWinConPty(true).setConPtyInheritCursor(false)
        .setRedirectErrorStream(true).start()
    private val bytes = ByteArrayOutputStream()
    private val executor = Executors.newSingleThreadExecutor()
    private val reading = executor.submit {
        process.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                synchronized(bytes) { bytes.write(buffer, 0, count) }
            }
        }
    }

    fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8) }
        .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        .replace(Regex("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)"), "")
        .replace("\r", "")

    fun awaitPassword() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!text().contains("Collector password (hidden;")) {
            check(process.isAlive && System.nanoTime() < deadline) { "Terminal password prompt was not reached" }
            Thread.sleep(20)
        }
        // The prompt is emitted just before Console.readPassword disables echo.
        Thread.sleep(150)
    }

    fun send(input: String) {
        process.outputStream.write(input.toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    fun finish(): TerminalResult {
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Terminal command did not finish" }
        reading.get(5, TimeUnit.SECONDS)
        return TerminalResult(process.exitValue(), text())
    }

    override fun close() {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        process.outputStream.close()
        process.inputStream.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Terminal reader did not stop" }
    }
}

internal data class TerminalResult(val exit: Int, val output: String) {
    fun event(): JsonObject {
        val start = output.indexOf('{')
        check(start >= 0) { "Terminal command returned no JSON" }
        return json.parseToJsonElement(output.substring(start).trim()).jsonObject
    }
}
