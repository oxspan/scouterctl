package dev.scouterctl

import dev.scouterctl.application.summarizeSql
import dev.scouterctl.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqlSummaryTest : FunSpec({
    test("SQL aggregates preserve signed hashes, wide duration totals, errors and profile scope") {
        val profile = ProfileRecord("9223372036854775806", listOf(
            ProfileStep("SQL3", 0, SqlProfile(10, Int.MAX_VALUE, 0)),
            ProfileStep("SQL", 1, SqlProfile(-7, 20, -99)),
            ProfileStep("SQL3", 2, SqlProfile(10, Int.MAX_VALUE, 7)),
            ProfileStep("SQL", 3, SqlProfile(-7, -3, 0)),
            ProfileStep("SOCKET", 4),
        ), completeness = ProfileCompleteness.TRUNCATED)

        val summary = summarizeSql(profile, false)

        summary.txid shouldBe profile.txid
        summary.completeness shouldBe ProfileCompleteness.TRUNCATED
        summary.totalSteps shouldBe 5
        summary.sqlSteps shouldBe 4
        summary.nonSqlSteps shouldBe 1
        summary.groups shouldBe listOf(
            SqlAggregate(10, null, false, 2, 4_294_967_294L, Int.MAX_VALUE, 1),
            SqlAggregate(-7, null, false, 2, 17L, 20, 1),
        )
    }

    for (steps in listOf(emptyList(), listOf(ProfileStep("MESSAGE", 0), ProfileStep("SOCKET", 1)))) {
        test("a profile with ${steps.size} non-SQL steps has no invented SQL groups") {
            val summary = summarizeSql(ProfileRecord("42", steps), true)
            summary.txid shouldBe "42"
            summary.totalSteps shouldBe steps.size
            summary.sqlSteps shouldBe 0
            summary.nonSqlSteps shouldBe steps.size
            summary.groups shouldBe emptyList()
        }
    }

    test("statement resolution distinguishes consistent, conflicting, missing and mismatched dictionaries") {
        val sql = listOf(
            SqlProfile(7, 1, 0, statement = DictionaryText(7, "select a")),
            SqlProfile(7, 1, 0, statement = DictionaryText(7, "select b")),
            SqlProfile(8, 1, 0, statement = DictionaryText(999, "wrong statement")),
            SqlProfile(9, 1, 0, statement = DictionaryText(9, "select c")),
            SqlProfile(9, 1, 0, statement = DictionaryText(9, "select c")),
            SqlProfile(10, 1, 0),
            SqlProfile(11, 1, 0, statement = DictionaryText(11)),
        )
        val summary = summarizeSql(ProfileRecord("42", sql.mapIndexed { index, value -> ProfileStep("SQL3", index, value) }), true)
        val groups = summary.groups.associateBy { it.hash }
        groups.getValue(7).statementConflict shouldBe true
        groups.getValue(7).statement shouldBe DictionaryText(7)
        groups.getValue(8).statement shouldBe DictionaryText(8)
        groups.getValue(9).statementConflict shouldBe false
        groups.getValue(9).statement shouldBe DictionaryText(9, "select c")
        groups.getValue(10).statement shouldBe DictionaryText(10)
        groups.getValue(11).statement shouldBe DictionaryText(11)
    }

    test("redacted grouping cannot reveal supplied SQL text or text conflicts") {
        val profile = ProfileRecord("42", listOf(
            ProfileStep("SQL3", 0, SqlProfile(7, 1, -9, statement = DictionaryText(7, "private statement a"), error = DictionaryText(-9, "private error"))),
            ProfileStep("SQL3", 1, SqlProfile(7, 1, 0, statement = DictionaryText(7, "private statement b"))),
        ))
        summarizeSql(profile, false).groups.single() shouldBe SqlAggregate(7, null, false, 2, 2, 1, 1)
    }

    test("equal duration groups use signed hash order for stable output") {
        val profile = ProfileRecord("42", listOf(ProfileStep("SQL3", 0, SqlProfile(9, 3, 0)),
            ProfileStep("SQL3", 1, SqlProfile(-7, 3, 0))))
        summarizeSql(profile, false).groups.map { it.hash } shouldBe listOf(-7, 9)
    }
})
