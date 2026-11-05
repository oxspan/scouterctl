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
    NONE, VERSION, AUTH, SESSION, MISSING_META, INVALID_META, STALLED_CURSOR, DISCONNECT, EMPTY_OBJECTS,
    STRUCTURAL_ONLY, MISSING_TEXT, EMPTY_TEXT, MANY_SQL, TEXT_WRONG_TYPE, TEXT_WRONG_HASH, TEXT_DUPLICATE,
    TEXT_PACK, TEXT_DISCONNECT, TEXT_SESSION, XLOG_MISSING, XLOG_WRONG_ID,
    PROFILE_TRUNCATED, PROFILE_OTHER_CONTROL, PROFILE_MANY_STEPS, PROFILE_MISSING, PROFILE_MISSING_ONCE, PROFILE_DISCONNECT, PROFILE_MALFORMED, TEXT_TIMEOUT,
}

class WireFixture(private val fault: Fault = Fault.NONE, private val expectedPassword: String? = null) : AutoCloseable {
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
                                request.put("version", if (fault == Fault.VERSION) "2.8.1 test" else "2.21.3 test")
                                request.put("timezone", "Coordinated Universal Time")
                                send(output, request)
                            }
                            RequestCmd.OBJECT_LIST_REAL_TIME -> if (fault != Fault.EMPTY_OBJECTS) send(output, ObjectPack().apply {
                                objHash = 42; objName = "/fixture"; objType = "java"; address = "private-address"
                            })
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
