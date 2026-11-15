package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.value.*

internal fun decodeRuntimeSettings(
    response: List<Pack>,
    valueIsVisible: (String) -> Boolean,
    textValueIsVisible: (String) -> Boolean = { false },
): List<RuntimeSetting> {
    val pack = response.singleOrNull() as? MapPack
        ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected one runtime settings response")
    fun items(key: String): ListValue = pack.get(key) as? ListValue
        ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid runtime settings fields")
    val keys = items("key")
    val values = items("value")
    val defaults = items("default")
    if (keys.size() > 5000 || keys.size() != values.size() || keys.size() != defaults.size()) {
        throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Inconsistent runtime settings lists")
    }
    val seen = mutableSetOf<String>()
    fun invalidValue(): Nothing = throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid operational setting value")
    fun visibleValue(key: String, value: Value): String {
        if (textValueIsVisible(key)) return (value as? TextValue)?.value ?: invalidValue()
        return when (value) {
            is DecimalValue -> value.value.toString()
            is BooleanValue -> value.value.toString()
            else -> invalidValue()
        }
    }
    return (0 until keys.size()).map { index ->
        val key = (keys.get(index) as? TextValue)?.value
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid runtime setting key")
        if (key.isEmpty() || key.length > 256 || !seen.add(key)) {
            throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid or duplicate runtime setting key")
        }
        val visible = valueIsVisible(key)
        RuntimeSetting(key, if (visible) visibleValue(key, values.get(index)) else null,
            if (visible) visibleValue(key, defaults.get(index)) else null, !visible)
    }
}
