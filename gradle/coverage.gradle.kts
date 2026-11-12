import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import javax.xml.parsers.DocumentBuilderFactory

val coverageEnabled = providers.gradleProperty("coverage").map(String::toBoolean).getOrElse(false)
val coverageDirectory = layout.buildDirectory.dir("jacoco")
val coverageSummary = layout.buildDirectory.file("reports/jacoco/summary.md")
val minimumLineCoverage = "0.90".toBigDecimal()
val minimumBranchCoverage = "0.80".toBigDecimal()

extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.14" }

val coverageAgent = tasks.register<Sync>("coverageAgent") {
    from({ zipTree(configurations.getByName("jacocoAgent").singleFile) })
    include("jacocoagent.jar")
    into(layout.buildDirectory.dir("coverage-agent"))
}

tasks.named<Test>("test") {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = coverageEnabled
        includes = listOf("dev.scouterctl.*")
        destinationFile = coverageDirectory.get().file("test.exec").asFile
    }
    if (coverageEnabled) {
        dependsOn(coverageAgent)
        outputs.dir(coverageDirectory)
        // A coverage run must execute the whole suite and never reuse old subprocess data.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Coverage requires fresh JVM execution data") { true }
        systemProperty("coverage.agent", layout.buildDirectory.file("coverage-agent/jacocoagent.jar").get().asFile.absolutePath)
        systemProperty("coverage.directory", coverageDirectory.get().asFile.absolutePath)
        doFirst {
            check(!providers.gradleProperty("cliJar").isPresent) { "Coverage requires the JAR built from this checkout" }
            delete(coverageDirectory, layout.buildDirectory.dir("reports/jacoco"))
            coverageDirectory.get().asFile.mkdirs()
        }
    }
}

val coverageData = tasks.register("coverageData") {
    dependsOn("test")
    doLast {
        check(coverageEnabled) { "Enable coverage with -Pcoverage=true" }
        val directory = coverageDirectory.get().asFile
        check(directory.resolve("test.exec").let { it.isFile && it.length() > 0 }) {
            "Missing test JVM coverage data"
        }
        check(directory.listFiles().orEmpty().any { it.name.startsWith("cli-") && it.extension == "exec" && it.length() > 0 }) {
            "Missing CLI subprocess coverage data"
        }
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(coverageData)
    executionData.setFrom(fileTree(coverageDirectory) { include("*.exec") })
    reports { xml.required.set(true); html.required.set(true) }
}

val summarizeCoverage = tasks.register("coverageSummary") {
    dependsOn("jacocoTestReport")
    doLast {
        val report = tasks.named<JacocoReport>("jacocoTestReport").get().reports.xml.outputLocation.get().asFile
        check(report.isFile) { "Missing JaCoCo XML report" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val root = factory.newDocumentBuilder().parse(report).documentElement
        val counters = (0 until root.childNodes.length).map { root.childNodes.item(it) }
            .filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == "counter" }
            .associateBy { it.getAttribute("type") }
        val rows = listOf("LINE" to minimumLineCoverage, "BRANCH" to minimumBranchCoverage).map { (type, minimum) ->
            val counter = checkNotNull(counters[type]) { "Missing $type coverage counter" }
            val covered = counter.getAttribute("covered").toLong()
            val total = covered + counter.getAttribute("missed").toLong()
            check(total > 0) { "Empty $type coverage data" }
            val ratio = covered.toBigDecimal().divide(total.toBigDecimal(), 8, java.math.RoundingMode.HALF_UP)
            val percent = ratio.multiply(100.toBigDecimal()).setScale(2, java.math.RoundingMode.HALF_UP)
            "| $type | $covered / $total | $percent% | ${minimum * 100.toBigDecimal()}% | ${if (ratio >= minimum) "PASS" else "FAIL"} |"
        }
        val summary = "## Test coverage\n\n| Metric | Covered / total | Coverage | Minimum | Result |\n| --- | --- | --- | --- | --- |\n" +
            rows.joinToString("\n") + "\n\nIncludes the test JVM and CLI subprocesses. Excludes optional real-Collector smoke tests.\n"
        coverageSummary.get().asFile.apply { parentFile.mkdirs(); writeText(summary) }
        logger.lifecycle(summary)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(summarizeCoverage)
    executionData.setFrom(fileTree(coverageDirectory) { include("*.exec") })
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = minimumLineCoverage }
            limit { counter = "BRANCH"; minimum = minimumBranchCoverage }
        }
    }
}

tasks.register("coverageCheck") {
    group = "verification"
    description = "Run tests, report coverage, and enforce line/branch minimums (requires -Pcoverage=true)."
    dependsOn("jacocoTestCoverageVerification")
}
if (coverageEnabled) tasks.named("check") { dependsOn("coverageCheck") }
