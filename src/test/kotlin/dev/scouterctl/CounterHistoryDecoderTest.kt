package dev.scouterctl

import dev.scouterctl.adapters.collector.decodeDatedObjects
import dev.scouterctl.core.CounterTarget
import dev.scouterctl.core.ErrorCode
import dev.scouterctl.core.OperationFailure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.value.DecimalValue
import scouter.lang.value.TextValue
import scouter.lang.value.Value

class CounterHistoryDecoderTest : FunSpec({
    val invalidResponses: List<Pair<String, List<Pack>>> = listOf(
        "absent response" to emptyList(),
        "multiple response packs" to listOf(datedObjects(), datedObjects()),
        "absent hashes" to listOf(datedObjects(hashes = null)),
        "absent names" to listOf(datedObjects(names = null)),
        "absent types" to listOf(datedObjects(types = null)),
        "scalar column" to listOf(datedObjects().apply { put("objHash", TextValue("private-value")) }),
        "mismatched name count" to listOf(datedObjects(names = emptyList())),
        "mismatched type count" to listOf(datedObjects(types = emptyList())),
        "excessive object count" to listOf(datedObjects(hashes = List(10001) { DecimalValue(42) })),
        "non-numeric hash" to listOf(datedObjects(hashes = listOf(TextValue("private-value")))),
        "hash below signed 32-bit range" to listOf(datedObjects(hashes = listOf(DecimalValue(Int.MIN_VALUE.toLong() - 1)))),
        "hash above signed 32-bit range" to listOf(datedObjects(hashes = listOf(DecimalValue(Int.MAX_VALUE.toLong() + 1)))),
        "non-text name" to listOf(datedObjects(names = listOf(DecimalValue(42)))),
        "non-text type" to listOf(datedObjects(types = listOf(DecimalValue(42)))),
    )
    invalidResponses.forEach { (description, response) ->
        test("dated object discovery rejects $description with a sanitized protocol error") {
            val failure = shouldThrow<OperationFailure> { decodeDatedObjects(response) }
            failure.code shouldBe ErrorCode.PROTOCOL_ERROR
            failure.message shouldBe "Invalid dated object list response"
        }
    }
    test("dated object discovery accepts an empty result") {
        decodeDatedObjects(listOf(datedObjects(emptyList(), emptyList(), emptyList()))) shouldBe emptyList()
    }
    test("dated object discovery preserves signed 32-bit hash boundaries") {
        for (hash in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            decodeDatedObjects(listOf(datedObjects(hashes = listOf(DecimalValue(hash.toLong()))))) shouldBe
                listOf(CounterTarget(hash, "/fixture", "java"))
        }
    }
})

private fun datedObjects(
    hashes: List<Value>? = listOf(DecimalValue(42)),
    names: List<Value>? = listOf(TextValue("/fixture")),
    types: List<Value>? = listOf(TextValue("java")),
): MapPack = MapPack().apply {
    listOf("objHash" to hashes, "objName" to names, "objType" to types).forEach { (column, values) ->
        if (values != null) {
            val list = newList(column)
            values.forEach { list.add(it) }
        }
    }
}
