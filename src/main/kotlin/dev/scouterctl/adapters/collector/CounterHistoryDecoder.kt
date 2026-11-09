package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.value.*

internal fun decodeDatedObjects(response: List<Pack>): List<CounterTarget> {
    val objects = response.singleOrNull() as? MapPack ?: invalidDatedObjects()
    val hashes = objects.get("objHash") as? ListValue ?: invalidDatedObjects()
    val names = objects.get("objName") as? ListValue ?: invalidDatedObjects()
    val types = objects.get("objType") as? ListValue ?: invalidDatedObjects()
    if (hashes.size() > 10000 || names.size() != hashes.size() || types.size() != hashes.size()) invalidDatedObjects()
    return (0 until hashes.size()).map { index ->
        val hash = (hashes.get(index) as? DecimalValue)?.value ?: invalidDatedObjects()
        if (hash !in Int.MIN_VALUE..Int.MAX_VALUE) invalidDatedObjects()
        val name = (names.get(index) as? TextValue)?.value ?: invalidDatedObjects()
        val type = (types.get(index) as? TextValue)?.value ?: invalidDatedObjects()
        CounterTarget(hash.toInt(), name, type)
    }
}

private fun invalidDatedObjects(): Nothing =
    throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid dated object list response")

internal fun decodeCounterTarget(objHash: Int, targets: List<CounterTarget>): CounterTarget {
    val matches = targets.filter { it.objHash == objHash }
    if (matches.size > 1) invalidHistory()
    return matches.singleOrNull()
        ?: throw OperationFailure(ErrorCode.NOT_FOUND, "Object was not found on the requested date")
}

internal fun decodeCounterHistory(query: CounterHistoryQuery, target: CounterTarget, metadata: List<Pack>, response: List<Pack>): CounterHistory {
    val engine = decodeCounterEngine(metadata)
    val known = engine.isCounterOf(query.counter, target.objType)
    val definition = CounterDefinition(query.counter,
        if (known) engine.getCounterDisplayName(target.objType, query.counter) else null,
        if (known) engine.getCounterUnit(target.objType, query.counter) else null)
    val pack = response.singleOrNull() as? MapPack ?: invalidHistory()
    val times = pack.get("time") as? ListValue ?: invalidHistory()
    val values = pack.get("value") as? ListValue ?: invalidHistory()
    if (times.size() > 100000 || times.size() != values.size()) invalidHistory()
    val points = (0 until times.size()).map { index ->
        val time = (times.get(index) as? DecimalValue)?.value ?: invalidHistory()
        if (time < query.from || time >= query.to) invalidHistory()
        val number = decodeCounterNumber(values.get(index))
        CounterPoint(time, number, if (number == null) CounterAvailability.UNSUPPORTED else CounterAvailability.AVAILABLE)
    }
    return CounterHistory(target, definition, points)
}

private fun invalidHistory(): Nothing = throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid counter history response")
