package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

class UnexpectedDecodeTest : FunSpec({
    test("unknown profile step preserves the committed manifest without recording a new failure") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        WireFixture(Fault.PROFILE_MALFORMED).use { server ->
            val result = ContractCli(work).run(server,
                listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow)
            result.exit shouldBe 6
            val error = result.events.single()["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "PROTOCOL_ERROR"
            error["message"]!!.jsonPrimitive.content shouldBe "Response could not be decoded"
            result.stderr shouldBe ""
        }
        val checkpoint = EvidenceStore(directory).load()
        checkpoint.pages.sumOf { it.records } shouldBe 2
        checkpoint.cursor.txid shouldBe WireFixture.ids[1].toString()
        checkpoint.executionComplete shouldBe false
        checkpoint.lastFailure shouldBe null
    }
})
