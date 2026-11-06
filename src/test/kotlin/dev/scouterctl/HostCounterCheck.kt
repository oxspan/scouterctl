package dev.scouterctl

import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.adapters.serialization.toDto
import dev.scouterctl.adapters.serialization.json
import kotlinx.serialization.encodeToString
import dev.scouterctl.core.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun verifyHostCounters(work: Path, endpoint: Endpoint, password: String, javaHash: Int, httpPort: Int) {
    var hostHash = 0
    awaitHost("Host Agent discovery") {
        CollectorSource.connect(endpoint, password, BuildInfo.version).use { source ->
            source.objects().singleOrNull { it.objName == "/scouterctl-host-fixture" && it.alive }
                ?.also { hostHash = it.objHash } != null
        }
    }
    fun read(hash: Int) = CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.realtimeCounters(hash) }
    val configs = listOf("conf/scouter.conf", "conf/account.xml", "agent.conf", "host/host.conf").associateWith { Files.readAllBytes(work.resolve(it)) }
    val cli = AgentCli(work, endpoint.port, password)
    cli.run("host-before", listOf("counter", "list", "--object-hash", hostHash.toString()))
    cli.run("process-before", listOf("counter", "get", "--object-hash", javaHash.toString(), "--counter", "ProcCpu"))
    val requireIo = System.getenv("SCOUTER_HOST_EXPECT_IO") != "false"
    val started = System.nanoTime()
    val running = AtomicBoolean(true)
    val requests = AtomicInteger()
    var observedHost = read(hostHash)
    var observedProcess = read(javaHash)
    var networkBytes = 0L
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build().use { http ->
        Executors.newSingleThreadExecutor().use { executor ->
            val task = executor.submit {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55)
                while (running.get() && System.nanoTime() < deadline) {
                    val pending = List(16) {
                        http.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort/poc/sql"))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding())
                    }
                    for (future in pending) {
                        check(future.get(8, TimeUnit.SECONDS).statusCode() == 200)
                        requests.incrementAndGet()
                    }
                }
            }
            try {
                val file = work.resolve("disk-load.bin")
                try {
                    FileChannel.open(file, CREATE_NEW, WRITE).use { channel ->
                        val buffer = ByteBuffer.wrap(ByteArray(64 * 1024) { (it % 251).toByte() })
                        repeat(1024) { buffer.rewind(); while (buffer.hasRemaining()) channel.write(buffer) }
                        channel.force(true)
                    }
                } finally { Files.deleteIfExists(file) }
                System.getenv("SCOUTER_HOST_NETWORK_URL")?.let { url ->
                    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build()
                    val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    check(response.statusCode() == 200)
                    response.body().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            networkBytes += count
                            check(networkBytes <= 64L * 1024 * 1024)
                        }
                    }
                }
                awaitHost("real Host and process samples") {
                    observedHost = read(hostHash)
                    observedProcess = read(javaHash)
                    val info = CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info }
                    Files.writeString(work.resolve("host-observed.json"), json.encodeToString(observedHost.toDto(info)))
                    Files.writeString(work.resolve("process-observed.json"), json.encodeToString(observedProcess.toDto(info)))
                    val ioReady = if (requireIo) observedHost.number("DiskWriteBytes") > 0 &&
                        (networkBytes == 0L || observedHost.number("NetRxBytes") > 0)
                        else System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(25)
                    observedProcess.number("ProcCpu") > 0 && observedHost.number("Cpu") >= 0 &&
                        observedHost.number("MemU") > 0 && ioReady
                }
                for (name in listOf("Cpu", "MemU", "DiskReadBytes", "DiskWriteBytes", "NetRxBytes", "NetTxBytes")) {
                    val row = observedHost.counters.single { it.name == name }
                    if (!requireIo && name in setOf("DiskReadBytes", "DiskWriteBytes", "NetRxBytes", "NetTxBytes"))
                        check(row.availability == CounterAvailability.UNAVAILABLE && row.value == null) { "Unexpected I/O sample $name" }
                    else check(row.availability == CounterAvailability.AVAILABLE && row.value!!.toDouble() >= 0) { "Unavailable host counter $name" }
                }
                cli.run("host-loaded", listOf("counter", "list", "--object-hash", hostHash.toString()))
                cli.run("process-loaded", listOf("counter", "get", "--object-hash", javaHash.toString(), "--counter", "ProcCpu"))
                verifyHostNative(work, endpoint, password, hostHash, javaHash)
            } finally { running.set(false); task.get(12, TimeUnit.SECONDS) }
        }
    }
    for ((name, before) in configs) check(Files.readAllBytes(work.resolve(name)).contentEquals(before))
    check(requests.get() > 0)
    Files.writeString(work.resolve("host-summary.json"), buildJsonObject {
        put("collectorVersion", CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.version })
        put("agentJava", Files.readString(work.resolve("http-port.java-version")))
        put("hostHash", hostHash); put("javaHash", javaHash); put("httpRequests", requests.get())
        put("diskBytesWrittenAndSynced", 64 * 1024 * 1024); put("networkResponseBytes", networkBytes)
        put("configurationUnchanged", true); put("processCpu", observedProcess.number("ProcCpu")); put("ioAvailable", requireIo)
        put("hostCounters", buildJsonObject {
            for (name in listOf("Cpu", "MemU", "DiskReadBytes", "DiskWriteBytes", "NetRxBytes", "NetTxBytes")) {
                val row = observedHost.counters.single { it.name == name }
                put(name, buildJsonObject { put("value", row.value); put("unit", row.unit); put("availability", row.availability.name.lowercase()) })
            }
        })
    }.toString())
    println("PASS: Host/process CPU and memory; I/O availability=$requireIo, actual load, unchanged configuration")
}

private fun RealtimeCounters.number(name: String): Double = counters.singleOrNull { it.name == name }?.value?.toDoubleOrNull() ?: -1.0

private fun awaitHost(label: String, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
    while (System.nanoTime() < deadline) {
        if (predicate()) return
        Thread.sleep(200)
    }
    error("Timed out waiting for $label")
}
