package dev.scouterctl

import kotlinx.serialization.json.*
import kotlin.math.abs

internal fun verifyScaleOutput(scenario: ScaleScenario, output: String) {
    val envelope = json.parseToJsonElement(output.trim()).jsonObject
    if (scenario.expectedExit != 0) {
        check("data" !in envelope) { "Rejected capture leaked comparison data" }
        val expected = if (scenario.corruptLastPage) "IO_ERROR" else "PARTIAL_RESULT"
        check(envelope["error"]!!.jsonObject["code"]!!.jsonPrimitive.content == expected)
        return
    }
    val comparison = json.decodeFromJsonElement<CaptureComparison>(envelope["data"]!!)
    val perService = scenario.records / scenario.services
    check(scenario.records % scenario.services == 0)
    check(comparison.before.capturedRecords == scenario.records && comparison.after.capturedRecords == scenario.records)
    check(comparison.before.capturedErrors == scenario.records / 10)
    check(comparison.after.capturedErrors == scenario.records * 3 / 10)
    check(comparison.before.totalServices == scenario.services && comparison.after.totalServices == scenario.services)
    check(comparison.before.unavailableProfiles == 0 && comparison.after.unavailableProfiles == 0)
    check(comparison.before.unknownServiceNames == 0 && comparison.after.unknownServiceNames == 0)
    check(comparison.complete && !comparison.textIncluded)
    check(comparison.warnings == listOf(ComparisonWarning.TRAFFIC_COVERAGE_UNVERIFIED))
    val union = scenario.services + scenario.afterOffset
    check(comparison.totalServices == union && comparison.truncated == (union > 1000))
    check(comparison.services.map { it.serviceHash } == (1..minOf(union, 1000)).toList())
    comparison.services.forEach { row ->
        val before = checkNotNull(row.before)
        check(before.requests == perService && before.errors == perService / 10)
        check(before.elapsedTotalMs == perService * 100L && before.averageElapsedMs == 100.0 && before.maxElapsedMs == 100)
        check(before.serviceName == null && !row.identityConflict)
        if (scenario.afterOffset != 0) {
            check(row.presence == ServicePresence.BEFORE_ONLY && row.after == null && row.delta == null)
        } else {
            val after = checkNotNull(row.after)
            check(after.requests == perService && after.errors == perService * 3 / 10)
            check(after.elapsedTotalMs == perService * 150L && after.averageElapsedMs == 150.0 && after.maxElapsedMs == 150)
            check(after.serviceName == null && row.presence == ServicePresence.BOTH)
            val delta = checkNotNull(row.delta)
            check(delta.requests == 0 && delta.errors == perService * 2 / 10)
            check(abs(delta.errorRatePercentagePoints - 20.0) < 1e-9)
            check(delta.elapsedTotalMs == perService * 50L && delta.averageElapsedMs == 50.0 && delta.maxElapsedMs == 50L)
        }
    }
}
