package dev.scouterctl

import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.core.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun verifyRealtimeCounters(work: Path, endpoint: Endpoint, password: String, objHash: Int, httpPort: Int) {
    val files = listOf("conf/scouter.conf", "conf/account.xml", "agent.conf").associateWith { Files.readAllBytes(work.resolve(it)) }
    fun snapshot() = CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.realtimeCounters(objHash) }
    var observed = snapshot()
    awaitCounters("JVM counters") {
        observed = snapshot()
        observed.counters.any { it.name == "HeapUsed" && it.availability == CounterAvailability.AVAILABLE && it.value!!.toDouble() > 0 }
    }
    val cli = AgentCli(work, endpoint.port, password)
    val target = listOf("--object-hash", objHash.toString())
    cli.run("counter-baseline", listOf("counter", "list") + target)
    val heap = cli.run("counter-heap", listOf("counter", "get") + target + listOf("--counter", "HeapUsed"))
        .single()["data"]!!.jsonObject["counters"]!!.jsonArray.single().jsonObject
    check(heap["availability"] == JsonPrimitive("available") && heap["unit"] == JsonPrimitive("MB"))
    check(heap["value"]!!.jsonPrimitive.double > 0)
    cli.run("counter-missing", listOf("counter", "get") + target + listOf("--counter", "scouterctl_missing"), 7)
    cli.run("counter-missing-target", listOf("counter", "list", "--object-hash", "0"), 7)
    AgentCli(work, endpoint.port, "wrong-$password").run("counter-rejected", listOf("counter", "list") + target, 3)
    val requests = AtomicInteger()
    val errors = AtomicInteger()
    val load = System.getenv("SCOUTER_COUNTER_LOAD") == "true"
    var loaded: RealtimeCounters? = null
    if (load) {
        val running = AtomicBoolean(true)
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
            Executors.newSingleThreadExecutor().use { executor ->
                val task = executor.submit {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
                    while (running.get() && System.nanoTime() < deadline) {
                        val routes = List(12) { "/poc/slow" } + List(4) { "/poc/sql" } + List(4) { "/poc/error" }
                        val pending = routes.map { route ->
                            route to http.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort$route"))
                                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding())
                        }
                        for ((route, future) in pending) {
                            val status = future.get(8, TimeUnit.SECONDS).statusCode()
                            check(status == if (route == "/poc/error") 500 else 200)
                            requests.incrementAndGet()
                            if (status == 500) errors.incrementAndGet()
                        }
                    }
                }
                try {
                    awaitCounters("traffic counters") {
                        loaded = snapshot()
                        fun value(name: String) = loaded.counters.singleOrNull { it.name == name }?.value?.toDoubleOrNull() ?: 0.0
                        value("TPS") > 0 && value("ElapsedTime") > 0 && value("ErrorRate") > 0
                    }
                    val result = cli.run("counter-loaded", listOf("counter", "list") + target).single()["data"]!!.jsonObject
                    for (name in listOf("TPS", "ElapsedTime", "ErrorRate")) {
                        val row = result["counters"]!!.jsonArray.map { it.jsonObject }.single { it["name"] == JsonPrimitive(name) }
                        check(row["availability"] == JsonPrimitive("available") && row["value"]!!.jsonPrimitive.double > 0)
                    }
                } finally { running.set(false); task.get(12, TimeUnit.SECONDS) }
            }
        }
        check(requests.get() > 0 && errors.get() > 0)
    }
    for ((name, before) in files) check(Files.readAllBytes(work.resolve(name)).contentEquals(before))
    for (label in listOf("counter-baseline", "counter-heap") + if (load) listOf("counter-loaded") else emptyList()) {
        val text = Files.readString(work.resolve("$label.jsonl"))
        check(!text.contains(password) && !text.contains(work.toString()))
    }
    Files.writeString(work.resolve("counter-summary.json"), buildJsonObject {
        put("collectorVersion", CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.version })
        put("agentJava", Files.readString(work.resolve("http-port.java-version")))
        put("baselineCounters", observed.counters.size); put("heapUnit", "MB"); put("heapAvailable", true)
        put("missingCounterExit", 7); put("missingTargetExit", 7); put("invalidCredentialsExit", 3)
        put("configurationUnchanged", true); put("trafficChecked", load); put("httpRequests", requests.get()); put("httpErrors", errors.get())
        if (loaded != null) for (name in listOf("TPS", "ElapsedTime", "ErrorRate")) {
            val row = loaded.counters.single { it.name == name }
            put(name, row.value); put(name + "Unit", row.unit)
        }
    }.toString())
    println("PASS: counter list/get, units, heap, missing targets/keys, authentication, unchanged files; traffic=$load")
}

private fun awaitCounters(label: String, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
    while (System.nanoTime() < deadline) {
        if (predicate()) return
        Thread.sleep(200)
    }
    error("Timed out waiting for $label")
}
