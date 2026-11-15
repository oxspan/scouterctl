package dev.scouterctl

import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.adapters.serialization.json
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.core.*
import kotlinx.serialization.json.*
import scouter.io.DataOutputX
import scouter.lang.pack.XLogPack
import scouter.net.NetCafe
import scouter.util.CipherUtil
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected a built Scouter source directory and a new evidence directory" }
    val upstream = Path.of(args[0]).toAbsolutePath().normalize()
    val evidence = Path.of(args[1]).toAbsolutePath().normalize()
    Files.createDirectory(evidence)
    val collectorJava = requireNotNull(System.getenv("SCOUTER_COLLECTOR_JAVA"))
    val classpath = listOf(upstream.resolve("scouter.server/target/scouter-server-2.21.3.jar"),
        upstream.resolve("scouter.common/target/scouter-common-2.21.3.jar"),
        upstream.resolve("scouter.server/target/lib/*")).joinToString(java.io.File.pathSeparator)
    val outcomes = listOf("UTC", "America/Chicago").map { zone ->
        verifyStorageCalendar(evidence.resolve(zone.replace('/', '-')), zone, collectorJava, classpath)
    }
    Files.writeString(evidence.resolve("summary.json"), JsonArray(outcomes).toString())
    println("PASS: real Collector UTC control and Chicago summer storage-date divergence; evidence=$evidence")
}

private fun verifyStorageCalendar(work: Path, zone: String, collectorJava: String, classpath: String): JsonObject {
    Files.createDirectories(work.resolve("conf"))
    Files.createDirectory(work.resolve("tmp"))
    val tcp = ServerSocket(0).use { it.localPort }
    val udp = DatagramSocket(0).use { it.localPort }
    val password = UUID.randomUUID().toString()
    val account = work.resolve("conf/account.xml")
    Files.writeString(account, "<Accounts><Account id=\"timezone-check\" pass=\"${CipherUtil.sha256(password)}\" group=\"guest\"><Email></Email></Account></Accounts>")
    Files.writeString(work.resolve("conf/scouter.conf"), """
        net_tcp_listen_ip=127.0.0.1
        net_tcp_listen_port=$tcp
        net_udp_listen_ip=127.0.0.1
        net_udp_listen_port=$udp
        net_http_server_enabled=false
        net_http_api_enabled=false
        server_id=timezone-storage-check
        mgr_purge_enabled=false
        db_dir=${work.resolve("database")}
        log_dir=${work.resolve("logs")}
    """.trimIndent())
    val collector = ProcessBuilder(collectorJava, "-XX:-UsePerfData", "-Xmx128m", "-Duser.timezone=$zone",
        "-Djava.io.tmpdir=${work.resolve("tmp")}", "-cp", classpath, "scouter.server.Main")
        .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("collector.log").toFile()).start()
    try {
        val endpoint = Endpoint("127.0.0.1", tcp, "timezone-check", 2000)
        awaitTimezoneCollector("login", collector) {
            CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.serverId == "timezone-storage-check" }
        }
        val instant = Instant.parse("2026-07-01T05:30:00Z")
        val from = instant.toEpochMilli()
        val storageDate = if (zone == "UTC") "20260701" else "20260630"
        val storageZone = if (zone == "UTC") "UTC" else "-06:00"
        DatagramSocket().use { socket ->
            val pack = XLogPack().apply { objHash = 42; txid = 57001; gxid = 57001; endTime = from; service = 10; elapsed = 30 }
            val bytes = DataOutputX().apply { write(NetCafe.CAFE); writePack(pack) }.toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), udp))
        }
        val storedQuery = Query(storageDate, from, from + 1000, storageZone, 42, 10)
        awaitTimezoneCollector("stored transaction", collector) {
            CollectorSource.connect(endpoint, password, BuildInfo.version).use {
                it.page(storedQuery, Cursor()).records.map(XLogRecord::txid) == listOf("57001")
            }
        }
        val stored = CollectorSource.connect(endpoint, password, BuildInfo.version).use {
            it.page(storedQuery, Cursor()).records.map(XLogRecord::txid)
        }
        Files.writeString(work.resolve("stored-partition.json"), buildJsonObject {
            put("date", storageDate); put("from", from); put("to", from + 1000)
            put("txids", JsonArray(stored.map(::JsonPrimitive)))
        }.toString())
        fun cli(label: String, arguments: List<String>, exit: Int): List<JsonObject> {
            val output = work.resolve("$label.jsonl")
            val errors = work.resolve("$label.stderr")
            val command = listOf(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-XX:-UsePerfData",
                "-Djava.io.tmpdir=${work.resolve("tmp")}", "-jar", System.getProperty("cli.jar")) + arguments +
                listOf("--port", tcp.toString(), "--user", "timezone-check", "--no-input")
            Files.writeString(work.resolve("$label.command.json"), JsonArray(command.map(::JsonPrimitive)).toString())
            val child = ProcessBuilder(command).directory(work.toFile()).redirectOutput(output.toFile())
                .redirectError(errors.toFile()).apply {
                    environment()["SCOUTERCTL_PASSWORD"] = password
                    environment()["SCOUTERCTL_CONFIG"] = work.resolve("config.json").toString()
                }.start()
            child.outputStream.close()
            try {
                check(child.waitFor(15, TimeUnit.SECONDS)) { "$label timed out" }
                check(child.exitValue() == exit) { "$label: ${Files.readString(output)}" }
                check(Files.readString(errors).isEmpty())
                return Files.readAllLines(output).map { json.parseToJsonElement(it).jsonObject }
            } finally { if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        }
        val doctor = cli("doctor", listOf("doctor"), 0).single()
        check(doctor["data"]!!.jsonObject["timezone"]!!.jsonObject["id"] == JsonPrimitive(zone))
        val window = listOf("--date", "20260701", "--from", instant.toString(), "--to", instant.plusSeconds(1).toString(),
            "--timezone", zone, "--object-hash", "42")
        val events = cli("civil-query", listOf("xlog", "search") + window, 0)
        val rows = events.count { it["type"] == JsonPrimitive("record") }
        check(rows == if (zone == "UTC") 1 else 0)
        for (event in listOf(events.first(), events.last())) {
            check(event["timezoneCheck"]!!.jsonObject["status"] == JsonPrimitive("reported-offset-match"))
            check(event["storageDateVerification"] == JsonPrimitive("unverified"))
        }
        if (zone != "UTC") {
            val failure = cli("mismatch", listOf("xlog", "search") + window.map { if (it == zone) "UTC" else it }, 2).single()
            check(failure["error"]!!.jsonObject["code"] == JsonPrimitive("INVALID_ARGUMENT"))
        }
        return buildJsonObject {
            put("collectorZone", zone); put("instant", instant.toString()); put("civilDate", "20260701")
            put("storageDate", storageDate); put("storedRecords", stored.size); put("cliRecords", rows)
            put("timezoneCheck", "reported-offset-match"); put("storageDateVerification", "unverified")
        }
    } finally {
        collector.destroy()
        if (!collector.waitFor(10, TimeUnit.SECONDS)) collector.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        Files.deleteIfExists(account)
        Files.writeString(work.resolve("teardown.json"), "{\"collectorStopped\":${!collector.isAlive},\"accountRemoved\":${!Files.exists(account)}}")
        check(!collector.isAlive)
    }
}

private fun awaitTimezoneCollector(label: String, collector: Process, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (System.nanoTime() < deadline && collector.isAlive) {
        try { if (ready()) return } catch (_: OperationFailure) { }
        Thread.sleep(100)
    }
    error("Collector $label did not become ready")
}
