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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal fun verifyAgentDiagnostics(work: Path, endpoint: Endpoint, password: String, objHash: Int, httpPort: Int) {
    val cli = AgentCli(work, endpoint.port, password)
    val target = listOf("--object-hash", objHash.toString())
    val before = listOf("conf/scouter.conf", "conf/account.xml", "agent.conf").associateWith { Files.readAllBytes(work.resolve(it)) }
    val empty = cli.run("active-empty", listOf("agent", "active-requests") + target).single()["data"]!!.jsonObject
    check(empty["requests"]!!.jsonArray.isEmpty())
    HttpClient.newHttpClient().use { http ->
        fun request(path: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort$path"))
            .timeout(Duration.ofSeconds(50)).build()
        val started = System.currentTimeMillis()
        val pending = http.sendAsync(request("/poc/hold"), HttpResponse.BodyHandlers.ofString())
        try {
            eventually("instrumented request entered diagnostic hold") {
                http.send(request("/diagnostics/hold-state"), HttpResponse.BodyHandlers.ofString()).body() == "waiting"
            }
            val structural = cli.run("active-structural", listOf("agent", "active-requests") + target).single()["data"]!!.jsonObject
            val active = structural["requests"]!!.jsonArray.single().jsonObject
            check(active["service"] == JsonNull && active["threadName"] == JsonNull)
            check(active["state"] == JsonPrimitive("TIMED_WAITING"))
            val txid = active["txid"]!!.jsonPrimitive.content
            check(txid.toLong() != 0L && active["threadId"]!!.jsonPrimitive.content.toLong() > 0)
            val named = cli.run("active-names", listOf("agent", "active-requests") + target + "--include-text")
                .single()["data"]!!.jsonObject["requests"]!!.jsonArray.single().jsonObject
            check(named["txid"]!!.jsonPrimitive.content == txid)
            check(named["service"]!!.jsonPrimitive.content == "/poc/hold")
            check(named["threadName"]!!.jsonPrimitive.content.isNotEmpty())
            val dump = cli.run("thread-dump", listOf("agent", "thread-dump") + target).single()["data"]!!.jsonObject
            val lines = dump["lines"]!!.jsonArray.map { it.jsonPrimitive.content }
            check(lines.any { it.contains("dev.scouterctl.fixture.TestService.execute") })
            check(lines.any { it.contains("java.util.concurrent.CountDownLatch.await") })
            check(dump["completeness"] == JsonPrimitive("unknown"))
            check(http.send(request("/diagnostics/release"), HttpResponse.BodyHandlers.ofString()).statusCode() == 200)
            check(pending.get(10, TimeUnit.SECONDS).statusCode() == 200)
            val ended = System.currentTimeMillis() + 1
            val date = Instant.ofEpochMilli(started).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
            val query = Query(date, started, ended, "UTC", objHash, 100)
            var stored: XLogRecord? = null
            eventually("completed held request stored as the same XLog transaction") {
                CollectorSource.connect(endpoint, password, BuildInfo.version).use { source ->
                    val currentQuery = query.copy(to = System.currentTimeMillis() + 1)
                    stored = source.page(currentQuery, Cursor()).records.singleOrNull { it.txid == txid }
                    Files.writeString(work.resolve("diagnostics-xlog-query.json"), buildJsonObject {
                        put("from", started); put("httpCompletedToExclusive", ended); put("queryToExclusive", currentQuery.to)
                        put("expectedTxid", txid); put("storedTxid", stored?.txid); put("storedEndTime", stored?.endTime)
                    }.toString())
                    stored != null
                }
            }
            val after = cli.run("active-after", listOf("agent", "active-requests") + target).single()["data"]!!.jsonObject
            check(after["requests"]!!.jsonArray.isEmpty())
            cli.run("diagnostics-missing", listOf("agent", "thread-dump", "--object-hash", Int.MIN_VALUE.toString()), 7)
            AgentCli(work, endpoint.port, "wrong-$password").run("diagnostics-auth", listOf("agent", "active-requests") + target, 3)
            for ((file, bytes) in before) check(Files.readAllBytes(work.resolve(file)).contentEquals(bytes))
            Files.writeString(work.resolve("diagnostics-summary.json"), buildJsonObject {
                put("collectorVersion", structural["collector"]!!.jsonObject["version"]!!)
                put("agentVersion", structural["agent"]!!.jsonObject["version"]!!)
                put("agentJvm", Files.readString(work.resolve("http-port.java-version")))
                put("txid", txid); put("activeRequestMatchedStoredXLog", true)
                put("storedEndTime", checkNotNull(stored).endTime)
                put("activeBefore", 0); put("activeDuring", 1); put("activeAfter", 0)
                put("structuralNamesHidden", true); put("explicitNamesReturned", true)
                put("threadDumpContainsHeldRequest", true); put("threadDumpLines", lines.size)
                put("threadDumpCompleteness", "unknown"); put("configurationUnchanged", true)
                put("missingTargetExit", 7); put("invalidCredentialsExit", 3)
            }.toString())
            println("PASS: live active request, stable transaction ID through stored XLog, held-request thread dump, empty snapshots and unchanged configuration")
        } finally {
            http.send(request("/diagnostics/release"), HttpResponse.BodyHandlers.discarding())
            pending.cancel(true)
        }
    }
}
