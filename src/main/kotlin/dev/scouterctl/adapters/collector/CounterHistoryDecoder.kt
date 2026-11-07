package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.value.*

internal fun decodeCounterTarget(objHash: Int, targets: List<Pack>): CounterTarget {
    val objects = targets.singleOrNull() as? MapPack ?: invalidHistory()
    val hashes = objects.get("objHash") as? ListValue ?: invalidHistory()
    val names = objects.get("objName") as? ListValue ?: invalidHistory()
    val types = objects.get("objType") as? ListValue ?: invalidHistory()
    if (hashes.size() > 10000 || names.size() != hashes.size() || types.size() != hashes.size()) invalidHistory()
    val matches = (0 until hashes.size()).map { index ->
        val hash = (hashes.get(index) as? DecimalValue)?.value ?: invalidHistory()
        if (hash !in Int.MIN_VALUE..Int.MAX_VALUE) invalidHistory()
        val name = (names.get(index) as? TextValue)?.value ?: invalidHistory()
        val type = (types.get(index) as? TextValue)?.value ?: invalidHistory()
        CounterTarget(hash.toInt(), name, type)
    }.filter { it.objHash == objHash }
    if (matches.size > 1) invalidHistory()
    val target = matches.singleOrNull() ?: throw OperationFailure(ErrorCode.NOT_FOUND, "Object was not found on the requested date")
    return target
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
