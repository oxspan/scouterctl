package dev.scouterctl

import dev.scouterctl.adapters.collector.decodeActiveRequests
import dev.scouterctl.adapters.collector.decodeThreadDump
import dev.scouterctl.core.AgentIdentity
import dev.scouterctl.core.ErrorCode
import dev.scouterctl.core.OperationFailure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.pack.ObjectPack
import scouter.lang.value.DecimalValue
import scouter.lang.value.NullValue
import scouter.lang.value.TextValue
import scouter.util.Hexa32

private val diagnosticAgent = AgentIdentity(42, "fixture", "java", true, "2.21.3")

private fun activeReply() = MapPack().apply {
    put("objHash", 42L)
    put("complete", true)
    newList("id").apply { add(91L); add(0L) }
    newList("elapsed").apply { add(12L); add(100L) }
    newList("stat").apply { add("RUNNABLE"); add("n/a") }
    newList("cpu").apply { add(5L); add(-1L) }
    newList("txid").apply { add(Hexa32.toString32(Long.MAX_VALUE)); add("z1") }
    newList("service").apply { add("/first"); add(NullValue()) }
    newList("name").apply { add("worker"); add(NullValue()) }
}

class AgentDiagnosticsDecoderTest : FunSpec({
    test("active requests preserve deferred rows, nullable names, unavailable CPU and descending elapsed order") {
        val rows = decodeActiveRequests(diagnosticAgent, listOf(activeReply()), true).requests
        rows.map { it.txid } shouldBe listOf("-1", Long.MAX_VALUE.toString())
        rows.map { it.threadId } shouldBe listOf("0", "91")
        rows.map { it.threadCpuTimeMs } shouldBe listOf(null, "5")
        rows.map { it.service } shouldBe listOf(null, "/first")
        rows.map { it.threadName } shouldBe listOf(null, "worker")
    }
    test("active snapshot rejects malformed columns, IDs and identity without leaking values") {
        val mutations: List<Pair<String, (MapPack) -> Unit>> = listOf(
            "wrong target" to { it.put("objHash", 43L) },
            "text target" to { it.put("objHash", "private-target") },
            "completion type" to { it.put("complete", "private-complete") },
            "missing column" to { it.put("cpu", "private-column") },
            "unequal rows" to { it.getList("id").add(8L) },
            "number type" to { it.getList("id").set(0, TextValue("private-id")) },
            "negative thread" to { it.getList("id").set(0, DecimalValue(-1)) },
            "negative duration" to { it.getList("elapsed").set(0, DecimalValue(-1)) },
            "invalid CPU" to { it.getList("cpu").set(0, DecimalValue(-2)) },
            "unknown state" to { it.getList("stat").set(0, TextValue("private-state")) },
            "state type" to { it.getList("stat").set(0, DecimalValue(1)) },
            "transaction syntax" to { it.getList("txid").set(0, TextValue("private-txid")) },
            "empty transaction" to { it.getList("txid").set(0, TextValue("")) },
            "noncanonical transaction" to { it.getList("txid").set(0, TextValue("x01")) },
            "duplicate transaction" to { it.getList("txid").set(0, TextValue("z1")) },
            "service type" to { it.getList("service").set(0, DecimalValue(1)) },
            "thread name type" to { it.getList("name").set(0, DecimalValue(1)) },
        )
        for ((label, mutate) in mutations) withClue(label) {
            val reply = activeReply().also(mutate)
            val error = shouldThrow<OperationFailure> { decodeActiveRequests(diagnosticAgent, listOf(reply), false) }
            error.code shouldBe ErrorCode.PROTOCOL_ERROR
            error.message.orEmpty() shouldNotContain "private-"
        }
    }
    test("diagnostic response cardinality and empty data distinguish unavailable from malformed") {
        val malformed: List<List<Pack>> = listOf(listOf(ObjectPack()), listOf(MapPack(), MapPack()))
        for (response in malformed) {
            shouldThrow<OperationFailure> { decodeActiveRequests(diagnosticAgent, response, false) }.code shouldBe ErrorCode.PROTOCOL_ERROR
            shouldThrow<OperationFailure> { decodeThreadDump(diagnosticAgent, response) }.code shouldBe ErrorCode.PROTOCOL_ERROR
        }
        shouldThrow<OperationFailure> { decodeActiveRequests(diagnosticAgent, emptyList(), false) }.code shouldBe ErrorCode.NOT_FOUND
        shouldThrow<OperationFailure> { decodeThreadDump(diagnosticAgent, emptyList()) }.code shouldBe ErrorCode.NOT_FOUND
        shouldThrow<OperationFailure> { decodeThreadDump(diagnosticAgent, listOf(MapPack().apply { newList("threadDump") })) }.code shouldBe ErrorCode.NOT_FOUND
    }
    test("thread dumps reject malformed lines and bound decoded content") {
        val replies = listOf(
            MapPack(),
            MapPack().apply { put("error", 7L) },
            MapPack().apply { newList("threadDump").add(7L) },
            MapPack().apply { newList("threadDump").apply { repeat(100001) { add("") } } },
            MapPack().apply { newList("threadDump").add("x".repeat(8_388_609)) },
        )
        for ((index, reply) in replies.withIndex()) withClue("malformed dump $index") {
            shouldThrow<OperationFailure> { decodeThreadDump(diagnosticAgent, listOf(reply)) }.code shouldBe ErrorCode.PROTOCOL_ERROR
        }
    }
})
