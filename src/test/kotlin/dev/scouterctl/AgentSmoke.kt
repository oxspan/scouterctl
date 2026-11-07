package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import scouter.util.CipherUtil
import scouter.util.HashUtil
import java.net.DatagramSocket
import java.net.ServerSocket
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/** End-to-end HTTP -> real javaagent -> Collector -> packaged CLI, without synthetic telemetry. */
fun main(args: Array<String>) {
    val upstream = Path.of(args.first()).toAbsolutePath().normalize()
    val work = Files.createTempDirectory("scouterctl-agent-smoke-")
    Files.createDirectory(work.resolve("conf"))
    val port = ServerSocket(0).use { it.localPort }
    val udp = DatagramSocket(0).use { it.localPort }
    val password = UUID.randomUUID().toString()
    Files.writeString(work.resolve("conf/account.xml"),
        "<Accounts><Account id=\"poc\" pass=\"${CipherUtil.sha256(password)}\" group=\"guest\"><Email></Email></Account></Accounts>")
    Files.writeString(work.resolve("conf/scouter.conf"), """
        net_tcp_listen_ip=127.0.0.1
        net_tcp_listen_port=$port
        net_udp_listen_ip=127.0.0.1
        net_udp_listen_port=$udp
        net_http_server_enabled=false
        net_http_api_enabled=false
        server_id=scouterctl-agent-smoke
        db_dir=${work.resolve("database")}
        log_dir=${work.resolve("logs")}
        mgr_purge_enabled=false
    """.trimIndent())
    Files.writeString(work.resolve("agent.conf"), """
        net_collector_ip=127.0.0.1
        net_collector_tcp_port=$port
        net_collector_udp_port=$udp
        obj_name=scouterctl-fixture
        obj_host_name=scouterctl-test
        log_dir=${work.resolve("agent-logs")}
        hook_service_patterns=dev.scouterctl.fixture.TestService.execute
        hook_service_name_use_1st_string_enabled=true
        hook_method_patterns=dev.scouterctl.fixture.TestWork.work
        hook_jdbc_pstmt_classes=org.h2.jdbc.JdbcPreparedStatement
        hook_jdbc_stmt_classes=org.h2.jdbc.JdbcStatement
        hook_jdbc_rs_classes=org.h2.jdbc.JdbcResultSet
        trace_auto_service_enabled=false
        trace_sampling_enabled=false
        profile_step_max_count=1000
    """.trimIndent())
    val java = Path.of(System.getProperty("java.home"), "bin/java").toString()
    val collectorJava = System.getenv("SCOUTER_COLLECTOR_JAVA") ?: java
    val cp = System.getenv("SCOUTER_COLLECTOR_CLASSPATH") ?: listOf(upstream.resolve("scouter.server/target/scouter-server-2.21.3.jar"),
        upstream.resolve("scouter.common/target/scouter-common-2.21.3.jar"), upstream.resolve("scouter.server/target/lib/*")).joinToString(":")
    fun launchCollector() = ProcessBuilder(collectorJava, "-Xmx256m", "-Duser.timezone=UTC", "-cp", cp, "scouter.server.Main")
        .directory(work.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("collector.log").toFile())).start()
    var collector = launchCollector()
    var service: Process? = null
    println("Evidence: $work")
    try {
        val endpoint = Endpoint("127.0.0.1", port, "poc", 1500)
        eventually("Collector login") { CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.serverId == "scouterctl-agent-smoke" } }
        val agent = System.getenv("SCOUTER_AGENT_JAR")?.let(Path::of)
            ?: upstream.resolve("scouter.agent.java/target/scouter.agent.jar")
        check(Files.isRegularFile(agent)) { "Build Java 21 agent first: $agent" }
        val serviceJava = System.getenv("SCOUTER_SERVICE_JAVA") ?: java
        val serviceClasspath = System.getenv("SCOUTER_SERVICE_CLASSPATH") ?: System.getProperty("java.class.path")
        service = ProcessBuilder(serviceJava, "-Xmx128m", "-Duser.timezone=UTC", "-javaagent:$agent",
            "-Dscouter.config=${work.resolve("agent.conf")}", "-cp", serviceClasspath,
            "dev.scouterctl.fixture.TestServiceKt", work.resolve("http-port").toString())
            .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("service.log").toFile()).start()
        eventually("HTTP service startup") { Files.exists(work.resolve("http-port")) }
        val httpPort = Files.readString(work.resolve("http-port")).trim().toInt()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
            fun request(route: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort$route"))
                .timeout(Duration.ofSeconds(10)).GET().build()
            check(http.send(request("/health"), HttpResponse.BodyHandlers.ofString()).statusCode() == 200)
            var objectHash = 0
            eventually("instrumented object discovery") {
                CollectorSource.connect(endpoint, password, BuildInfo.version).use { source ->
                    source.objects().singleOrNull { it.objName.endsWith("/scouterctl-fixture") && it.alive }
                        ?.also { objectHash = it.objHash } != null
                }
            }
            if (args.drop(1) == listOf("--counter-history-only")) {
                fun stop(process: Process) {
                    process.destroy()
                    if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                    check(!process.isAlive) { "Fixture process did not stop" }
                }
                verifyCounterHistory(work, endpoint, password, objectHash, httpPort,
                    stopService = { stop(checkNotNull(service)) }, stopCollector = { stop(collector) },
                    startCollector = {
                        collector = launchCollector()
                        eventually("Collector restart") {
                            CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.serverId == "scouterctl-agent-smoke" }
                        }
                    })
                return
            }
            if (args.drop(1) == listOf("--counter-only")) {
                verifyRealtimeCounters(work, endpoint, password, objectHash, httpPort)
                return
            }
            if (args.drop(1) == listOf("--inspection-only")) {
                eventually("Agent JVM response") {
                    CollectorSource.connect(endpoint, password, BuildInfo.version).use {
                        it.agentStatus(objectHash).jvmAvailability == AgentJvmAvailability.AVAILABLE
                    }
                }
                verifyAgentInspection(work, endpoint, password, objectHash)
                return
            }
            val from = System.currentTimeMillis()
            val expected = linkedMapOf("/poc/ok" to 18, "/poc/slow" to 6, "/poc/error" to 6, "/poc/sql" to 6)
            val requests = expected.flatMap { (route, count) -> List(count) { route } }.map { route ->
                route to http.sendAsync(request(route), HttpResponse.BodyHandlers.ofString())
            }
            requests.forEach { (route, response) ->
                val result = response.get(15, TimeUnit.SECONDS)
                check(result.statusCode() == if (route == "/poc/error") 500 else 200) { "Unexpected HTTP outcome for $route" }
            }
            val httpCompletedAt = System.currentTimeMillis() + 1
            var to = httpCompletedAt
            val date = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
            eventually("all 36 agent-generated XLogs persisted") {
                to = System.currentTimeMillis() + 1
                CollectorSource.connect(endpoint, password, BuildInfo.version).use {
                    val observed = it.page(Query(date, from, to, "UTC", objectHash, 100), Cursor()).records
                    Files.writeString(work.resolve("ingestion-observed.json"), json.encodeToString(observed.map { it.toDto() }))
                    observed.size == 36
                }
            }
            val cli = AgentCli(work, port, password)
            cli.run("doctor", listOf("doctor"))
            cli.run("objects", listOf("object", "list"))
            val window = listOf("--date", date, "--from", Instant.ofEpochMilli(from).toString(), "--to", Instant.ofEpochMilli(to).toString(),
                "--timezone", "UTC", "--object-hash", objectHash.toString(), "--page-size", "5")
            val events = cli.run("xlogs", listOf("xlog", "search") + window)
            val rows = events.filter { it["type"]?.jsonPrimitive?.content == "record" }
                .map { json.decodeFromJsonElement<XLogRecordDto>(it["data"]!!).toModel() }
            check(rows.size == 36 && rows.map { it.txid }.toSet().size == 36)
            expected.forEach { (route, count) -> check(rows.count { it.serviceHash == HashUtil.hash(route) } == count) { "Wrong count for $route" } }
            check(rows.filter { it.serviceHash == HashUtil.hash("/poc/slow") }.all { it.elapsed >= 180 })
            check(rows.all { (it.errorHash != 0) == (it.serviceHash == HashUtil.hash("/poc/error")) })
            val boundary = rows.minOf { it.endTime }
            val boundaryWindow = window.toMutableList().apply {
                set(indexOf("--from") + 1, Instant.ofEpochMilli(boundary).toString())
                set(indexOf("--to") + 1, Instant.ofEpochMilli(boundary + 1).toString())
            }
            val boundaryRows = cli.run("xlogs-boundary", listOf("xlog", "search") + boundaryWindow)
                .filter { it["type"]?.jsonPrimitive?.content == "record" }
                .map { json.decodeFromJsonElement<XLogRecordDto>(it["data"]!!).toModel() }
            check(boundaryRows == rows.filter { it.endTime == boundary })
            val emptyWindow = window.toMutableList().apply { set(indexOf("--object-hash") + 1, "0") }
            val empty = cli.run("xlogs-empty", listOf("xlog", "search") + emptyWindow)
            check(empty.map { it["type"]!!.jsonPrimitive.content } == listOf("start", "complete"))
            for (route in expected.keys) {
                val row = rows.first { it.serviceHash == HashUtil.hash(route) }
                val event = cli.run("profile-${route.substringAfterLast('/')}", listOf("profile", "get", "--date", date, "--txid", row.txid)).single()
                val profile = json.decodeFromJsonElement<ProfileRecordDto>(event["data"]!!).toModel()
                check(profile.steps.any { it.type.startsWith("METHOD") }) { "Missing method instrumentation: $route" }
                if (route == "/poc/sql") check(profile.steps.any { it.type.startsWith("SQL") }) { "Missing JDBC instrumentation" }
            }
            val captureArgs = listOf("capture", "--directory", work.resolve("capture").toString()) + window
            cli.run("capture-first", captureArgs + listOf("--max-pages", "2"), 8)
            cli.run("capture-resume", captureArgs + listOf("--resume", "--max-pages", "40"))
            val manifest = json.decodeFromString<CaptureManifestDto>(Files.readString(work.resolve("capture/manifest.json"))).toModel()
            val captured = manifest.files.flatMap { Files.readAllLines(work.resolve("capture").resolve(it.name)) }
                .map { json.decodeFromString<XLogRecordDto>(it).toModel() }
            check(manifest.executionComplete && captured == rows)
            AgentCli(work, port, "invalid-fixture-password").run("wrong-password", listOf("doctor"), 3)
            val files = Files.list(work).use { paths -> paths.filter { it.toString().endsWith(".jsonl") }.toList() }
            check(files.all { file -> listOf(password, CipherUtil.sha256(password), "private-bind-marker", "fixture-error-marker")
                .none { Files.readString(file).contains(it) } })
            Files.writeString(work.resolve("summary.json"), buildJsonObject {
                put("requests", 36); put("uniqueXLogs", rows.size); put("captured", captured.size)
                put("pages", manifest.files.size); put("errors", rows.count { it.errorHash != 0 })
                put("methodAndJdbcProfiles", true); put("credentialsAndBindExcluded", true)
                put("receivedAfterHttpCompletion", rows.count { it.endTime >= httpCompletedAt })
                put("halfOpenBoundaryRecords", boundaryRows.size); put("emptyQueryComplete", true)
                put("agentJvm", Files.readString(work.resolve("http-port.java-version"))); put("collectorVersion", manifest.source.version)
            }.toString())
            println("PASS: 36 real HTTP requests, 36 unique XLogs, 6 errors, slow timings, method/JDBC profiles, 8-page capture/resume, auth failure, redaction")
        }
    } finally {
        for (process in listOfNotNull(service, collector)) {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            check(!process.isAlive) { "Fixture process did not stop" }
        }
        Files.deleteIfExists(work.resolve("conf/account.xml"))
        Files.writeString(work.resolve("teardown.json"), buildJsonObject {
            put("collectorPid", collector.pid()); put("collectorStopped", !collector.isAlive)
            put("servicePid", service?.pid()); put("serviceStopped", service?.isAlive != true)
        }.toString())
        println("Fixture processes stopped")
    }
}

private fun eventually(label: String, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
    var lastFailure: Exception? = null
    while (System.nanoTime() < deadline) {
        try { if (predicate()) return } catch (error: Exception) { lastFailure = error }
        Thread.sleep(100)
    }
    throw IllegalStateException("Timed out waiting for $label", lastFailure)
}
