package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.json.*
import scouter.io.DataOutputX
import scouter.lang.pack.ObjectPack
import scouter.lang.pack.Pack
import scouter.lang.pack.XLogPack
import scouter.lang.pack.XLogProfilePack
import scouter.lang.step.MessageStep
import scouter.lang.step.Step
import scouter.net.NetCafe
import scouter.util.CipherUtil
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Collector ingestion/CLI check, with synthetic wire telemetry, not agent instrumentation. */
fun main(args: Array<String>) {
    val upstream = Path.of(args.single()).toAbsolutePath().normalize()
    val work = Files.createTempDirectory("scouterctl-real-smoke-")
    Files.createDirectory(work.resolve("conf"))
    val port = ServerSocket(0).use { it.localPort }
    val udpPort = DatagramSocket(0).use { it.localPort }
    val password = UUID.randomUUID().toString()
    Files.writeString(work.resolve("conf/account.xml"),
        "<Accounts><Account id=\"poc\" pass=\"${CipherUtil.sha256(password)}\" group=\"guest\"><Email></Email></Account></Accounts>")
    Files.writeString(work.resolve("conf/scouter.conf"), """
        net_tcp_listen_ip=127.0.0.1
        net_tcp_listen_port=$port
        net_udp_listen_ip=127.0.0.1
        net_udp_listen_port=$udpPort
        net_http_server_enabled=false
        net_http_api_enabled=false
        server_id=scouterctl-smoke
        db_dir=${work.resolve("database")}
        log_dir=${work.resolve("logs")}
        mgr_purge_enabled=false
    """.trimIndent())
    val java = Path.of(System.getProperty("java.home"), "bin/java").toString()
    val classpath = listOf(upstream.resolve("scouter.server/target/scouter-server-2.21.3.jar"),
        upstream.resolve("scouter.common/target/scouter-common-2.21.3.jar"),
        upstream.resolve("scouter.server/target/lib/*")).joinToString(":")
    val collectorJava = System.getenv("SCOUTER_COLLECTOR_JAVA") ?: java
    val server = ProcessBuilder(collectorJava, "-Xmx256m", "-Duser.timezone=UTC", "-cp", classpath, "scouter.server.Main")
        .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("collector.log").toFile()).start()
    try {
        val endpoint = Endpoint("127.0.0.1", port, "poc", 1000)
        await("Collector login", server) { CollectorSource.connect(endpoint, password, BuildInfo.version).use { it.info.serverId == "scouterctl-smoke" } }
        val now = System.currentTimeMillis()
        val from = now / 1000 * 1000
        val to = from + 10_000
        val date = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
        DatagramSocket().use { socket ->
            fun send(pack: Pack) {
                val bytes = DataOutputX().apply { write(NetCafe.CAFE); writePack(pack) }.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), udpPort))
            }
            send(ObjectPack().apply { objHash = 42; objName = "/scouterctl/poc"; objType = "java"; version = "synthetic" })
            WireFixture.ids.forEachIndexed { index, id ->
                send(XLogProfilePack().apply {
                    txid = id; objHash = 42; time = from + index; service = 10
                    profile = Step.toBytes(arrayOf(MessageStep().apply { this.index = 1; message = "private marker" }))
                })
                send(XLogPack().apply {
                    txid = id; gxid = Long.MIN_VALUE + 1; caller = 9007199254740993L
                    objHash = 42; endTime = from + index; service = 10; elapsed = 30
                })
            }
        }
        val query = Query(date, from, to, "UTC", 42, 2)
        await("persisted telemetry", server) {
            CollectorSource.connect(endpoint, password, BuildInfo.version).use { source ->
                source.objects().any { it.objHash == 42 } && source.page(query.copy(pageSize = 10), Cursor()).records.size == 3 &&
                    WireFixture.ids.all { source.profile(date, it).steps.size == 1 }
            }
        }
        val window = listOf("--date", date, "--from", Instant.ofEpochMilli(from).toString(), "--to", Instant.ofEpochMilli(to).toString(),
            "--timezone", "UTC", "--object-hash", "42", "--page-size", "2")
        fun cli(label: String, arguments: List<String>, expected: Int): List<JsonObject> {
            val out = work.resolve("$label.jsonl")
            val err = work.resolve("$label.stderr")
            val child = ProcessBuilder(listOf(java, "-jar", System.getProperty("cli.jar")) + arguments +
                listOf("--port", port.toString(), "--user", "poc")).apply {
                environment()["SCOUTERCTL_PASSWORD"] = password
            }.redirectOutput(out.toFile()).redirectError(err.toFile()).start()
            try {
                check(child.waitFor(15, TimeUnit.SECONDS)) { "$label timeout" }
                check(child.exitValue() == expected) { "$label exit ${child.exitValue()}: ${Files.readString(out)}" }
                check(Files.readString(err).isEmpty())
                return Files.readAllLines(out).map { json.parseToJsonElement(it).jsonObject }
            } finally { child.destroyForcibly() }
        }
        cli("doctor", listOf("doctor"), 0)
        val objects = cli("objects", listOf("object", "list"), 0).single()["data"]!!.jsonArray
        check(objects.any { it.jsonObject["objName"]?.jsonPrimitive?.content == "/scouterctl/poc" })
        val xlogs = cli("xlogs", listOf("xlog", "search") + window, 0)
        val ids = xlogs.filter { it["type"]?.jsonPrimitive?.content == "record" }.map { it["data"]!!.jsonObject["txid"]!!.jsonPrimitive.content }
        check(ids.sorted() == WireFixture.ids.map(Long::toString).sorted())
        val profile = cli("profile", listOf("profile", "get", "--date", date, "--txid", ids.first()), 0)
        check(profile.single()["data"]!!.jsonObject["steps"]!!.jsonArray.size == 1)
        val captureArgs = listOf("capture", "--directory", work.resolve("capture").toString(), "--max-pages", "1") + window
        cli("capture-first", captureArgs, 8)
        cli("capture-resume", captureArgs + "--resume", 0)
        val manifest = json.decodeFromString<CaptureManifestDto>(Files.readString(work.resolve("capture/manifest.json"))).toModel()
        val capturedIds = manifest.files.flatMap { Files.readAllLines(work.resolve("capture").resolve(it.name)) }
            .map { json.decodeFromString<XLogRecordDto>(it).toModel().txid }
        check(capturedIds == ids)
        val referenceOutput = work.resolve("reference.txt")
        val probe = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "dev.scouterctl.ReferenceProbe",
            port.toString(), date, from.toString(), to.toString()).apply {
            environment()["SCOUTERCTL_PASSWORD"] = password
        }.redirectErrorStream(true).redirectOutput(referenceOutput.toFile()).start()
        try {
            check(probe.waitFor(15, TimeUnit.SECONDS) && probe.exitValue() == 0) { "Reference probe failed" }
            check(Files.readAllLines(referenceOutput) == listOf("objects=42", "txids=" + ids.joinToString(","), "steps=1"))
        } finally { probe.destroyForcibly() }
        println("PASS: real Collector 2.21.3 login/object/3 XLogs/profile/capture-resume + independent Java probe")
        println("Evidence: $work")
    } finally {
        server.destroy()
        if (!server.waitFor(10, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS) }
        Files.deleteIfExists(work.resolve("conf/account.xml"))
        println("Collector process stopped: ${!server.isAlive}")
    }
}

private fun await(label: String, server: Process, check: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (System.nanoTime() < deadline && server.isAlive) {
        try { if (check()) return } catch (_: Exception) { /* External collector readiness is eventually observable. */ }
        Thread.sleep(100)
    }
    error("Timed out waiting for $label (collector alive=${server.isAlive})")
}
