package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import scouter.lang.counters.CounterEngine
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.value.*
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal fun decodeCounters(agent: AgentIdentity, metadata: List<Pack>, response: List<Pack>): RealtimeCounters {
    val definition = metadata.singleOrNull() as? MapPack ?: invalidCounters()
    val engine = CounterEngine()
    for (field in listOf("default", "custom")) {
        val value = definition.get(field)
        if (value == null && field == "custom") continue
        val bytes = (value as? BlobValue)?.value ?: invalidCounters()
        if (bytes.isEmpty() && field == "custom") continue
        validateCounterXml(bytes)
        if (!engine.parse(bytes)) invalidCounters()
    }
    val values = if (agent.alive) decodeCounterValues(response) else emptyMap()
    val names = engine.getCounterSet(agent.objType) + values.keys
    if (names.size > 5000) invalidCounters()
    return RealtimeCounters(agent, names.map { name ->
        if (name.isBlank() || name.length > 256 || name.any(Char::isISOControl)) invalidCounters()
        val raw = values[name]
        val number = when (raw) {
            is DecimalValue -> raw.value.toString()
            is FloatValue -> raw.value.takeIf { it.isFinite() }?.toString() ?: invalidCounters()
            is DoubleValue -> raw.value.takeIf { it.isFinite() }?.toString() ?: invalidCounters()
            else -> null
        }
        val known = engine.isCounterOf(name, agent.objType)
        CounterReading(name, if (known) engine.getCounterDisplayName(agent.objType, name) else null,
            if (known) engine.getCounterUnit(agent.objType, name) else null, number,
            when {
                raw == null -> CounterAvailability.UNAVAILABLE
                number != null -> CounterAvailability.AVAILABLE
                else -> CounterAvailability.UNSUPPORTED
            })
    })
}

private fun decodeCounterValues(response: List<Pack>): Map<String, Value> {
    val pack = response.singleOrNull() as? MapPack ?: invalidCounters()
    val keys = pack.get("counter") as? ListValue ?: invalidCounters()
    val values = pack.get("value") as? ListValue ?: invalidCounters()
    if (keys.size() > 5000 || keys.size() != values.size()) invalidCounters()
    val result = mutableMapOf<String, Value>()
    for (index in 0 until keys.size()) {
        val name = (keys.get(index) as? TextValue)?.value ?: invalidCounters()
        if (result.put(name, values.get(index)) != null) invalidCounters()
    }
    return result
}

private fun validateCounterXml(bytes: ByteArray) {
    if (bytes.isEmpty() || bytes.size > 1_048_576) invalidCounters()
    // The pinned Scouter parser does not disable DTDs; validate the same bytes before it sees them.
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder().apply {
        setErrorHandler(object : DefaultHandler() {
            override fun error(error: SAXParseException): Nothing = throw error
            override fun fatalError(error: SAXParseException): Nothing = throw error
        })
    }
    val document = try { bytes.inputStream().use { builder.parse(it) } }
        catch (_: SAXException) { invalidCounters() }
    if (document.documentElement.tagName != "Counters") invalidCounters()
}

private fun invalidCounters(): Nothing = throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid counter response")
