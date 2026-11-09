package dev.scouterctl

import scouter.io.DataInputX
import scouter.io.DataOutputX
import scouter.lang.pack.*
import scouter.lang.step.MessageStep
import scouter.lang.step.SqlStep3
import scouter.lang.step.Step
import scouter.lang.step.StepControl
import scouter.net.NetCafe
import scouter.net.RequestCmd
import scouter.net.TcpFlag
import kotlinx.serialization.json.*
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Fault {
    COUNTER_EXPORT,
    HISTORY_EMPTY, HISTORY_MALFORMED, HISTORY_TIME_TYPE, HISTORY_OUTSIDE, HISTORY_NAN, HISTORY_TARGET_DUPLICATE,
    COUNTER_MALFORMED, COUNTER_DUPLICATE, COUNTER_NAN, COUNTER_XML_DTD, COUNTER_EMPTY, COUNTER_CUSTOM,
    AGENT_OFFLINE, AGENT_NO_RESPONSE, AGENT_JVM_MALFORMED, AGENT_DUPLICATE,
    NONE, STATUS_MALFORMED, CONFIG_MALFORMED, CONFIG_DUPLICATE, CONFIG_INVALID_VISIBLE, VERSION, AUTH, SESSION, MISSING_META, INVALID_META, STALLED_CURSOR, DISCONNECT, EMPTY_OBJECTS,
    STRUCTURAL_ONLY, MISSING_TEXT, EMPTY_TEXT, MANY_SQL, TEXT_WRONG_TYPE, TEXT_WRONG_HASH, TEXT_DUPLICATE,
    TEXT_PACK, TEXT_DISCONNECT, TEXT_SESSION, XLOG_MISSING, XLOG_WRONG_ID,
    PROFILE_TRUNCATED, PROFILE_OTHER_CONTROL, PROFILE_MANY_STEPS, PROFILE_MISSING, PROFILE_MISSING_ONCE, PROFILE_DISCONNECT, PROFILE_MALFORMED, TEXT_TIMEOUT,
}

class WireFixture(private val fault: Fault = Fault.NONE, private val expectedPassword: String? = null,
    private val collectorVersion: String = "2.21.3 test", private val xlogErrorHash: Int = 0) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort
    private val executor = Executors.newSingleThreadExecutor()
    private var disconnected = false
    private var profileMissed = false
    private val releaseResponse = CountDownLatch(1)
    @Volatile private var state = FixtureState()

    internal fun diagnostics(): JsonObject {
        val observed = state
        return buildJsonObject {
            put("phase", observed.phase)
            put("command", observed.command)
            put("connections", observed.connections)
            put("requests", observed.requests)
            put("completedRequests", observed.completedRequests)
            put("responsePackets", observed.responsePackets)
            put("phaseElapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - observed.changedAt))
            put("failure", observed.failure)
            put("listening", !server.isClosed)
        }
    }

    private fun phase(value: String) { state = state.copy(phase = value, changedAt = System.nanoTime()) }
    private fun send(out: DataOutputX, pack: Pack) {
        phase("writing_packet")
        out.writeByte(TcpFlag.HasNEXT.toInt()); out.writePack(pack)
        state = state.copy(responsePackets = state.responsePackets + 1)
    }

    private val future = executor.submit {
        try { serve() } catch (failure: Throwable) {
            state = state.copy(failure = when (failure) {
                is SocketTimeoutException -> "socket_timeout"
                is SocketException -> "socket_failure"
                is EOFException -> "eof"
                is IllegalStateException, is AssertionError -> "assertion"
                else -> "other"
            })
            phase("failed")
            throw failure
        }
    }

    private fun serve() {
        while (!server.isClosed) {
            phase("accepting")
            val socket = try { server.accept() } catch (_: SocketException) { break }
            state = state.copy(connections = state.connections + 1)
            socket.use {
                phase("reading_header")
                it.soTimeout = 5000
                val input = DataInputX(java.io.BufferedInputStream(it.getInputStream()))
                val output = DataOutputX(java.io.BufferedOutputStream(it.getOutputStream()))
                check(input.readInt() == NetCafe.TCP_CLIENT)
                try {
                    while (true) {
                        phase("reading_command")
                        val command = input.readText()
                        state = state.copy(command = when (command) {
                            RequestCmd.LOGIN -> "login"
                            RequestCmd.SERVER_STATUS -> "collector_status"
                            RequestCmd.LIST_CONFIGURE_SERVER -> "collector_config"
                            RequestCmd.LIST_CONFIGURE_WAS -> "agent_config"
                            RequestCmd.OBJECT_ENV -> "agent_jvm"
                            RequestCmd.OBJECT_LIST_REAL_TIME -> "objects"
                            RequestCmd.TRANX_LOAD_TIME_GROUP_V2 -> "xlog_page"
                            RequestCmd.TRANX_PROFILE -> "profile"
                            RequestCmd.XLOG_READ_BY_TXID -> "xlog_context"
                            RequestCmd.GET_TEXT_PACK -> "dictionary"
                            else -> "unknown"
                        }, requests = state.requests + 1, responsePackets = 0)
                        phase("reading_request")
                        input.readLong()
                        if (command != RequestCmd.LOGIN && fault == Fault.SESSION) {
                            output.writeByte(TcpFlag.INVALID_SESSION.toInt()); output.flush(); break
                        }
                        when (command) {
                            RequestCmd.LOGIN -> {
                                val request = input.readMapPack()
                                val passwordMatches = expectedPassword == null ||
                                    request.getText("pass") == scouter.util.CipherUtil.sha256(expectedPassword)
                                request.put("session", if (fault == Fault.AUTH || !passwordMatches) 0L else 123L)
                                request.put("server_id", "fixture"); request.put("type", "guest")
                                request.put("version", if (fault == Fault.VERSION) "99.0.0 test" else collectorVersion)
                                request.put("timezone", "Coordinated Universal Time")
                                send(output, request)
                            }
                            RequestCmd.SERVER_STATUS -> send(output, MapPack().apply {
                                put("used", 1024L); put("total", 4096L); put("time", baseTime)
                                if (fault == Fault.STATUS_MALFORMED) put("used", "private-status-value")
                            })
                            RequestCmd.GET_XML_COUNTER -> send(output, MapPack().apply {
                                val xml = if (fault == Fault.COUNTER_XML_DTD)
                                    """<!DOCTYPE Counters [<!ENTITY secret SYSTEM "file:///private/counter-secret">]><Counters>&secret;</Counters>"""
                                else """<Counters><Familys><Family name="javaee"><Counter name="ProcCpu" disp="CPU" unit="%"/>
                                    <Counter name="GcCount" unit="cnt"/><Counter name="HeapUsed" unit="MB"/></Family></Familys>
                                    <Types><ObjectType name="java" family="javaee"/></Types></Counters>"""
                                put("default", scouter.lang.value.BlobValue(xml.toByteArray()))
                                if (fault == Fault.COUNTER_CUSTOM) put("custom", scouter.lang.value.BlobValue(
                                    """<Counters><Types><ObjectType name="java" family="javaee"><Counter name="HeapUsed" disp="Custom heap" unit="bytes"/></ObjectType></Types></Counters>""".toByteArray()))
                            })
                            RequestCmd.OBJECT_LIST_LOAD_DATE -> {
                                val storageDate = input.readMapPack().getText("date")
                                if (fault != Fault.COUNTER_EXPORT) check(storageDate == "20260101")
                                send(output, MapPack().apply {
                                    val hashes = newList("objHash"); val names = newList("objName"); val types = newList("objType")
                                    repeat(if (fault == Fault.COUNTER_EXPORT && storageDate != "20260102") 0 else if (fault == Fault.HISTORY_TARGET_DUPLICATE) 2 else 1) {
                                        hashes.add(42L); names.add("/historical-fixture"); types.add("java")
                                    }
                                })
                            }
                            RequestCmd.COUNTER_PAST_TIME -> {
                                val request = input.readMapPack()
                                check(request.getInt("objHash") == 42)
                                if (fault != Fault.COUNTER_EXPORT) {
                                    check(request.getLong("stime") == 1767225600000L)
                                    check(request.getLong("etime") == 1767225659999L)
                                }
                                send(output, MapPack().apply {
                                    val times = newList("time"); val values = newList("value")
                                    if (fault == Fault.COUNTER_EXPORT) {
                                        times.add(request.getLong("stime")); values.add(9007199254740993L)
                                        times.add(request.getLong("etime")); values.add(0L)
                                    } else if (fault != Fault.HISTORY_EMPTY && request.getText("counter") != "missing") {
                                        times.add(1767225600000L); values.add(9007199254740993L)
                                        times.add(1767225610000L); values.add(0L)
                                        times.add(1767225630000L); values.add("private-history-text")
                                        times.add(1767225659999L); values.add(scouter.lang.value.DoubleValue(2.5))
                                        if (fault == Fault.HISTORY_MALFORMED) times.add(1767225601000L)
                                        if (fault == Fault.HISTORY_TIME_TYPE) times.set(0, scouter.lang.value.TextValue("private-time"))
                                        if (fault == Fault.HISTORY_OUTSIDE) times.set(0, scouter.lang.value.DecimalValue(1767225660000L))
                                        if (fault == Fault.HISTORY_NAN) values.set(0, scouter.lang.value.DoubleValue(Double.NaN))
                                    }
                                })
                            }
                            RequestCmd.COUNTER_REAL_TIME_OBJECT_ALL -> {
                                check(input.readMapPack().getInt("objHash") == 42)
                                check(fault != Fault.AGENT_OFFLINE)
                                send(output, MapPack().apply {
                                    val keys = newList("counter"); val values = newList("value")
                                    if (fault != Fault.COUNTER_EMPTY) {
                                        keys.add("HeapUsed"); values.add(9007199254740993L)
                                        keys.add("ProcCpu"); values.add(scouter.lang.value.FloatValue(0f))
                                        keys.add("pluginCounter"); values.add(scouter.lang.value.DoubleValue(2.5))
                                        keys.add("pluginText"); values.add("private-counter-value")
                                        if (fault == Fault.COUNTER_DUPLICATE) { keys.add("HeapUsed"); values.add(7L) }
                                        if (fault == Fault.COUNTER_MALFORMED) keys.add("extra")
                                        if (fault == Fault.COUNTER_NAN) values.set(1, scouter.lang.value.DoubleValue(Double.NaN))
                                    }
                                })
                            }
                            RequestCmd.OBJECT_ENV -> {
                                check(input.readMapPack().getInt("objHash") == 42)
                                check(fault != Fault.AGENT_OFFLINE)
                                if (fault != Fault.AGENT_NO_RESPONSE) send(output, MapPack().apply {
                                    put("java.version", "1.8.0_502"); put("java.vm.name", "OpenJDK 64-Bit Server VM")
                                    put("os.name", "Linux"); put("os.arch", "amd64")
                                    put("user.home", "/private/home"); put("work.dir", "/private/work")
                                    put("secret.token", "private-token")
                                    if (fault == Fault.AGENT_JVM_MALFORMED) put("java.version", 8L)
                                })
                            }
                            RequestCmd.LIST_CONFIGURE_SERVER, RequestCmd.LIST_CONFIGURE_WAS -> {
                                val agent = command == RequestCmd.LIST_CONFIGURE_WAS
                                if (agent) {
                                    check(input.readMapPack().getInt("objHash") == 42)
                                    check(fault != Fault.AGENT_OFFLINE)
                                }
                                if (!agent || fault != Fault.AGENT_NO_RESPONSE) send(output, MapPack().apply {
                                newList("key").apply {
                                    add("smtp_password"); add(if (agent) "net_collector_tcp_port" else "net_tcp_listen_port"); add("db_dir")
                                    if (fault == Fault.CONFIG_DUPLICATE) add("db_dir")
                                }
                                newList("value").apply {
                                    add("private-config-marker"); add(6100L); add("/private/collector/data")
                                    if (fault == Fault.CONFIG_DUPLICATE) add("duplicate-private-marker")
                                    if (fault == Fault.CONFIG_INVALID_VISIBLE) set(1, scouter.lang.value.TextValue("private-visible-marker"))
                                }
                                newList("default").apply {
                                    add("private-default-marker"); add(6100L)
                                    if (fault != Fault.CONFIG_MALFORMED) add("/private/default")
                                    if (fault == Fault.CONFIG_DUPLICATE) add("duplicate-private-default")
                                }
                                })
                            }
                            RequestCmd.OBJECT_LIST_REAL_TIME -> if (fault != Fault.EMPTY_OBJECTS) {
                                repeat(if (fault == Fault.AGENT_DUPLICATE) 2 else 1) {
                                    send(output, ObjectPack().apply {
                                        objHash = 42; objName = "/fixture"; objType = "java"; address = "private-address"
                                        version = "2.8.1"; alive = fault != Fault.AGENT_OFFLINE
                                    })
                                }
                            }
                            RequestCmd.TRANX_LOAD_TIME_GROUP_V2 -> {
                                val request = input.readMapPack()
                                val txid = request.getLong("txid")
                                val start = if (txid == 0L) 0 else ids.indexOf(txid) + 1
                                check(start >= 0)
                                if (start > 0) check(request.getLong("lastBucketTime") == baseTime)
                                if (fault == Fault.DISCONNECT && start > 0 && !disconnected) {
                                    disconnected = true
                                    break
                                }
                                val end = minOf(start + request.getInt("pageCount"), ids.size)
                                for (index in start until end) send(output, XLogPack().apply {
                                    this.txid = ids[index]; gxid = Long.MIN_VALUE + 1; caller = 9007199254740993L
                                    objHash = 42; endTime = baseTime + index; elapsed = 30; service = 10
                                    if (index == 0) error = xlogErrorHash
                                    text1 = "secret-token"; ipaddr = byteArrayOf(127, 0, 0, 1)
                                })
                                if (fault != Fault.MISSING_META) send(output, MapPack().apply {
                                    put("hasMore", end < ids.size)
                                    put("lastXLogTime", if (fault == Fault.STALLED_CURSOR) 0L else baseTime)
                                    put("lastTxid", if (fault == Fault.STALLED_CURSOR) 0L else ids[end - 1])
                                    if (fault == Fault.INVALID_META) put("hasMore", "not-a-boolean")
                                })
                            }
                            RequestCmd.TRANX_PROFILE -> {
                                val request = input.readMapPack()
                                if (fault == Fault.PROFILE_DISCONNECT && request.getLong("txid") == ids.last() && !disconnected) {
                                    disconnected = true
                                    break
                                }
                                val missing = request.getLong("txid") == ids[1] &&
                                    (fault == Fault.PROFILE_MISSING || (fault == Fault.PROFILE_MISSING_ONCE && !profileMissed))
                                if (missing) profileMissed = true else send(output, XLogProfilePack().apply {
                                    profile = if (fault == Fault.PROFILE_TRUNCATED || fault == Fault.PROFILE_OTHER_CONTROL) Step.toBytes(arrayOf(
                                        SqlStep3().apply { index = 1; hash = 123; elapsed = 7 },
                                        StepControl().apply { code = if (fault == Fault.PROFILE_TRUNCATED) 0 else 7; message = " ** Profile Truncated ** " }
                                    )) else if (fault == Fault.PROFILE_MANY_STEPS) Step.toBytes(Array(1001) { number ->
                                        SqlStep3().apply { index = number; hash = 123; elapsed = 1 }
                                    }) else if (fault == Fault.PROFILE_MALFORMED && request.getLong("txid") == ids.last()) byteArrayOf(127)
                                    else if (fault == Fault.MANY_SQL) Step.toBytes(Array(101) { number ->
                                        SqlStep3().apply { index = number; hash = number }
                                    }) else Step.toBytes(arrayOf(
                                        MessageStep().apply { index = 1; message = "password=secret-token" },
                                        SqlStep3().apply {
                                            index = 2; hash = 123; elapsed = 7; error = 0; updated = 1
                                            param = "password=secret-token"
                                        },
                                        SqlStep3().apply {
                                            index = 3; hash = 456; elapsed = 11; error = -789; updated = -3
                                            param = "password=secret-token"
                                        }))
                                })
                            }
                            RequestCmd.XLOG_READ_BY_TXID -> {
                                check(fault != Fault.STRUCTURAL_ONLY) { "Default profile must not fetch text context" }
                                val request = input.readMapPack()
                                check(request.getText("date") == "20261101")
                                if (fault != Fault.XLOG_MISSING) send(output, XLogPack().apply {
                                    txid = if (fault == Fault.XLOG_WRONG_ID) 99L else request.getLong("txid")
                                    service = 10; error = -789
                                })
                            }
                            RequestCmd.GET_TEXT_PACK -> {
                                check(fault != Fault.STRUCTURAL_ONLY) { "Default profile must not query dictionaries" }
                                val request = input.readMapPack()
                                check(request.getText("date") == "20261101")
                                val type = request.getText("type")
                                val hashes = request.getList("hash")
                                check(hashes.size() in 1..100)
                                if (fault == Fault.TEXT_TIMEOUT) {
                                    phase("holding_response")
                                    releaseResponse.await()
                                    break
                                }
                                if (fault == Fault.TEXT_DISCONNECT) { phase("closing_connection"); break }
                                if (fault == Fault.TEXT_SESSION) {
                                    output.writeByte(TcpFlag.INVALID_SESSION.toInt()); output.flush(); break
                                }
                                if (fault == Fault.TEXT_PACK) send(output, MapPack()) else {
                                    for (i in 0 until hashes.size()) {
                                        val hash = hashes.getInt(i)
                                        val value = when (type) {
                                            "sql" -> if (fault == Fault.MANY_SQL) "statement $hash" else when (hash) {
                                                123 -> "select marker from fixture where id = ?"
                                                456 -> "insert into fixture(id) values (?)"
                                                else -> error("Unexpected SQL hash")
                                            }
                                            "error" -> { check(hash == -789); "fixture duplicate key error" }
                                            "service" -> { check(hash == 10); "/fixture<GET>" }
                                            else -> error("Unexpected dictionary type")
                                        }
                                        if (fault == Fault.EMPTY_TEXT ||
                                            (fault == Fault.MISSING_TEXT && type == "sql" && hash == 456)) continue
                                        val pack = TextPack(if (fault == Fault.TEXT_WRONG_TYPE) "method" else type,
                                            if (fault == Fault.TEXT_WRONG_HASH) -999 else hash, value)
                                        send(output, pack)
                                        if (fault == Fault.TEXT_DUPLICATE) send(output, pack)
                                    }
                                }
                            }
                            else -> error("Unexpected or unsafe command: $command")
                        }
                        phase("flushing_response")
                        output.writeByte(TcpFlag.NoNEXT.toInt()); output.flush()
                        state = state.copy(completedRequests = state.completedRequests + 1)
                    }
                } catch (_: EOFException) {
                    // Client closes its connection after each command invocation.
                }
            }
        }
    }
    override fun close() {
        server.close()
        releaseResponse.countDown()
        try { future.get(10, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    companion object {
        val ids = listOf(9223372036854775804L, 9223372036854775805L, 9223372036854775806L)
        const val baseTime = 1793491200000L // 2026-11-01T00:00:00Z
    }
}

private data class FixtureState(
    val phase: String = "starting", val command: String = "none",
    val connections: Int = 0, val requests: Int = 0, val completedRequests: Int = 0,
    val responsePackets: Int = 0, val failure: String = "none", val changedAt: Long = System.nanoTime(),
)
