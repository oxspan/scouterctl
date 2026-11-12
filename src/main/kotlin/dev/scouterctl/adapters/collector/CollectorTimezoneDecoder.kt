package dev.scouterctl.adapters.collector

import dev.scouterctl.application.describeCollectorTimezone
import dev.scouterctl.core.*
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.value.DecimalValue
import scouter.lang.value.TextValue

internal fun decodeCollectorTimezone(environment: List<Pack>, clock: List<Pack>): CollectorTimezone? {
    if (environment.isEmpty()) return null
    val properties = environment.singleOrNull() as? MapPack
        ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Collector environment response")
    val value = properties.get("user.timezone") ?: return null
    val id = (value as? TextValue)?.value
        ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Collector time zone field")
    val time = ((clock.singleOrNull() as? MapPack)?.get("time") as? DecimalValue)?.value
        ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Collector clock response")
    if (time < 0) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Collector clock value")
    return describeCollectorTimezone(id, time)
}
