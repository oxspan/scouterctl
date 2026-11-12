package dev.scouterctl

import dev.scouterctl.application.describeCollectorTimezone
import dev.scouterctl.application.validateCollectorTimezone
import dev.scouterctl.core.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class TimezoneRulesTest : FunSpec({
    val summer = Instant.parse("2026-07-01T12:00:00Z").toEpochMilli()
    for ((id, canonical, offset) in listOf(Triple("America/New_York", "America/New_York", -14400),
        Triple("GMT+05:45", "GMT+05:45", 20700), Triple("PST", "America/Los_Angeles", -25200))) {
        test("reported $id uses client zone rules at the Collector reference instant") {
            describeCollectorTimezone(id, summer) shouldBe CollectorTimezone(canonical, offset, summer)
        }
    }
    for (id in listOf("", "private-invalid-zone", "Central Standard Time")) {
        test("unresolvable reported zone '$id' has no invented metadata") {
            describeCollectorTimezone(id, summer) shouldBe null
        }
    }
    test("matching request offsets do not depend on the Collector's current seasonal offset") {
        val winter = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val collector = describeCollectorTimezone("America/New_York", winter)
        collector?.utcOffsetSeconds shouldBe -18000
        validateCollectorTimezone("America/New_York", summer, summer + 1000, collector)
        validateCollectorTimezone("Asia/Tokyo", summer, summer + 1000, describeCollectorTimezone("Asia/Seoul", winter))
    }
    test("offset differences inside an interval are rejected even when both endpoints agree") {
        val start = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        val error = shouldThrow<OperationFailure> {
            validateCollectorTimezone("America/Panama", start, end, describeCollectorTimezone("America/New_York", start))
        }
        error.code shouldBe ErrorCode.INVALID_ARGUMENT
    }
    for (includeTransition in listOf(false, true)) {
        test("DST offset check respects the exclusive end; transition included=$includeTransition") {
            val start = Instant.parse("2026-03-08T06:00:00Z").toEpochMilli()
            val end = Instant.parse("2026-03-08T07:00:00Z").toEpochMilli() + if (includeTransition) 1 else 0
            val collector = describeCollectorTimezone("America/New_York", start)
            if (includeTransition) shouldThrow<OperationFailure> {
                validateCollectorTimezone("America/Panama", start, end, collector)
            }.code shouldBe ErrorCode.INVALID_ARGUMENT
            else validateCollectorTimezone("America/Panama", start, end, collector)
        }
    }
    test("missing Collector evidence does not invent an offset mismatch") {
        validateCollectorTimezone("UTC", summer, summer + 1000, null)
    }
})
