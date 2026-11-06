package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.application.*
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.apache.catalina.util.ServerInfo
import org.springframework.boot.SpringBootVersion
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

/** Spring MVC -> Servlet hook -> real JDBC instrumentation -> Collector -> packaged CLI. */
fun main(args: Array<String>) {
    val upstream = Path.of(args.first()).toAbsolutePath().normalize()
    val work = Files.createTempDirectory("scouterctl-spring-smoke-")
    val offline = SpringOfflineCheck(work)
    val comparison = SpringComparisonCheck(work)
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
        server_id=scouterctl-spring-smoke
        db_dir=${work.resolve("database")}
        log_dir=${work.resolve("logs")}
        mgr_purge_enabled=false
    """.trimIndent())
    Files.writeString(work.resolve("agent.conf"), """
        net_collector_ip=127.0.0.1
        net_collector_tcp_port=$port
        net_collector_udp_port=$udp
        obj_name=scouterctl-spring
        obj_host_name=scouterctl-test
        log_dir=${work.resolve("agent-logs")}
        hook_jdbc_pstmt_classes=org.h2.jdbc.JdbcPreparedStatement
        hook_jdbc_stmt_classes=org.h2.jdbc.JdbcStatement
        hook_jdbc_rs_classes=org.h2.jdbc.JdbcResultSet
        _trace_auto_service_enabled=false
        xlog_sampling_enabled=false
        profile_fullstack_service_error_enabled=true
        profile_fullstack_sql_error_enabled=true
    """.trimIndent())
    val java = Path.of(System.getProperty("java.home"), "bin/java").toString()
    val collectorJava = System.getenv("SCOUTER_COLLECTOR_JAVA") ?: java
    val cp = System.getenv("SCOUTER_COLLECTOR_CLASSPATH") ?: listOf(upstream.resolve("scouter.server/target/scouter-server-2.21.3.jar"),
        upstream.resolve("scouter.common/target/scouter-common-2.21.3.jar"), upstream.resolve("scouter.server/target/lib/*")).joinToString(":")
    var collectorStarts = 0
    fun startCollector(): Process = ProcessBuilder(collectorJava, "-Xmx256m", "-Duser.timezone=UTC", "-cp", cp, "scouter.server.Main")
        .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("collector-${++collectorStarts}.log").toFile()).start()
    var collector = startCollector()
    var service: Process? = null
    println("Evidence: $work")
    try {
        val endpoint = Endpoint("127.0.0.1", port, "poc", 1500)
        awaitSpring("Collector login") { CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.serverId == "scouterctl-spring-smoke" } }
        if ("--inspection-only" in args) {
            verifyCollectorInspection(work, endpoint, password)
            return
        }
        verifyInitialConnection(work, endpoint, password)
        val agent = upstream.resolve("scouter.agent.java/target/scouter.agent.jar")
        check(Files.isRegularFile(agent)) { "Build Java 21 agent first: $agent" }
        service = ProcessBuilder(java, "-Xmx256m", "-Duser.timezone=UTC", "-javaagent:$agent",
            "-Dscouter.config=${work.resolve("agent.conf")}", "-cp", System.getProperty("java.class.path"),
            "dev.scouterctl.springfixture.SpringServiceKt", work.resolve("http-port").toString())
            .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("service.log").toFile()).start()
        awaitSpring("Spring Boot and database ready; see service.log") { Files.exists(work.resolve("http-port")) }
        val httpPort = Files.readString(work.resolve("http-port")).trim().toInt()
        var objectHash = 0
        awaitSpring("Spring agent discovery") {
            CollectorSource.connect(endpoint, password, BuildInfo.version).use { source ->
                source.objects().singleOrNull { it.objName.endsWith("/scouterctl-spring") && it.alive }
                    ?.also { objectHash = it.objHash } != null
            }
        }
        val expected = linkedMapOf("/poc/ok" to 200, "/poc/sql" to 200, "/poc/app-error" to 500, "/poc/sql-error" to 500, "/poc/slow-sql" to 200)
        val from = System.currentTimeMillis()
        val outcomes = mutableListOf<JsonObject>()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
            expected.forEach { (route, status) -> repeat(3) {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort$route"))
                    .timeout(Duration.ofSeconds(10)).GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() == status) { "$route: HTTP ${response.statusCode()}, expected $status" }
                if (route == "/poc/sql") check(response.body() == "private-bind-marker")
                outcomes += buildJsonObject { put("route", route); put("status", response.statusCode()) }
            } }
        }
        Files.writeString(work.resolve("http-outcomes.json"), json.encodeToString(outcomes))
        var to = System.currentTimeMillis() + 1
        val date = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
        awaitSpring("21 Servlet-generated XLogs including error dispatches persisted") {
            to = System.currentTimeMillis() + 1
            CollectorSource.connect(endpoint, password, BuildInfo.version).use {
                val observed = it.page(Query(date, from, to, "UTC", objectHash, 100), Cursor()).records
                Files.writeString(work.resolve("ingestion-observed.json"), json.encodeToString(observed.map { it.toDto() }))
                observed.size == 21
            }
        }
        val cli = AgentCli(work, port, password)
        cli.run("doctor", listOf("doctor"))
        cli.run("objects", listOf("object", "list"))
        val window = listOf("--date", date, "--from", Instant.ofEpochMilli(from).toString(), "--to", Instant.ofEpochMilli(to).toString(),
            "--timezone", "UTC", "--object-hash", objectHash.toString(), "--page-size", "5")
        val rows = cli.run("xlogs", listOf("xlog", "search") + window)
            .filter { it["type"]?.jsonPrimitive?.content == "record" }.map { json.decodeFromJsonElement<XLogRecordDto>(it["data"]!!).toModel() }
        check(rows.size == 21 && rows.map { it.txid }.toSet().size == 21)
        val verified = mutableListOf<JsonObject>()
        val textCheck = SpringProfileTextCheck(cli, date)
        val transactionErrors = expected.mapValues { it.value == 500 } + ("/error" to false)
        transactionErrors.forEach { (route, expectedError) ->
            val serviceName = if (route == "/error") route else "$route<GET>"
            val matching = rows.filter { it.serviceHash == HashUtil.hash(serviceName) }
            val expectedCount = if (route == "/error") 6 else 3
            check(matching.size == expectedCount) { "Expected $expectedCount Servlet transactions for $route: $matching" }
            matching.forEachIndexed { index, row ->
                check((row.errorHash != 0) == expectedError) { "$route error flag: ${row.errorHash}" }
                val event = cli.run("profile-${route.substringAfterLast('/')}-${index + 1}",
                    listOf("profile", "get", "--date", date, "--txid", row.txid)).single()
                val profile = json.decodeFromJsonElement<ProfileRecordDto>(event["data"]!!).toModel()
                check(profile.txid == row.txid && profile.steps.isNotEmpty())
                val sql = profile.steps.mapNotNull { it.sql }
                if (route == "/poc/ok" || route == "/error") check(sql.isEmpty()) else {
                    check(sql.size == 1) { "$route expected one SQL step: $profile" }
                    val expectedSql = expectedSpringSql(route)
                    check(sql.single().hash == HashUtil.hash(expectedSql)) { "$route SQL identity differs: $profile" }
                    check(sql.single().elapsedMs >= 0)
                    if (route == "/poc/slow-sql") check(sql.single().elapsedMs >= 300 && row.elapsed >= 300)
                    check((sql.single().errorHash != 0) == (route == "/poc/sql-error")) { "$route SQL error flag differs: $profile" }
                    if (route == "/poc/sql-error") {
                        check(sql.single().errorHash == row.errorHash)
                        check(sql.single().updated == -3)
                    }
                }
                verified += buildJsonObject {
                    put("route", route); put("serviceName", serviceName); put("txid", row.txid); put("errorHash", row.errorHash)
                    put("profileSteps", profile.steps.size); put("sqlSteps", sql.size)
                    put("sqlErrors", sql.count { it.errorHash != 0 })
                }
                textCheck.verify(row, route)
            }
        }
        Files.writeString(work.resolve("profile-checks.json"), json.encodeToString(verified))
        if (args.drop(1) == listOf("--recovery")) {
            verifySpringRecovery(work, port, password, httpPort, window, rows,
                stopCollector = {
                    collector.destroy()
                    if (!collector.waitFor(10, TimeUnit.SECONDS)) collector.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                    check(!collector.isAlive)
                },
                restartCollector = {
                    collector = startCollector()
                    awaitSpring("Collector login after restart") {
                        CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.serverId == "scouterctl-spring-smoke" }
                    }
                })
        }
        offline.capture(cli, window)
        comparison.capture(cli, httpPort, objectHash)
        Files.list(work).use { files -> files.filter { it.toString().endsWith(".jsonl") }.forEach { file ->
            val output = Files.readString(file)
            val excluded = listOf(password, CipherUtil.sha256(password)) +
                if (file.fileName.toString().startsWith("text-profile-")) emptyList()
                else listOf("private-bind-marker", "private-application-error-marker")
            check(excluded
                .none { output.contains(it) }) { "Sensitive marker in $file" }
        } }
        Files.writeString(work.resolve("summary.json"), buildJsonObject {
            put("requests", 15); put("uniqueXLogs", rows.size); put("profiles", verified.size)
            put("errorDispatches", 6)
            put("applicationErrors", 3); put("sqlErrors", 3); put("sqlProfiles", 12); put("slowSqlProfiles", 3)
            put("servletAutomaticInstrumentation", true); put("customServiceHooks", false)
            put("credentialsExcluded", true); put("defaultFreeTextExcluded", true)
            put("textProfilesVerified", verified.size); put("sqlTextProfilesVerified", 12)
            put("springBoot", SpringBootVersion.getVersion()); put("tomcat", ServerInfo.getServerNumber())
            put("agentJvm", System.getProperty("java.version"))
        }.toString())
        println("PASS: 15 real Spring MVC requests, 21 Servlet XLogs/profiles, 12 SQL texts including 3 slow queries, 3 application and 3 SQL errors")
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
    offline.verify()
    comparison.verify()
}

private fun awaitSpring(label: String, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
    var lastFailure: Exception? = null
    while (System.nanoTime() < deadline) {
        try { if (predicate()) return } catch (error: Exception) { lastFailure = error }
        Thread.sleep(100)
    }
    throw IllegalStateException("Timed out waiting for $label", lastFailure)
}
