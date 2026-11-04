package dev.scouterctl

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Serializable
internal data class ScaleMeasurement(val exit: Int, val elapsedMs: Long, val peakBytes: Long, val memorySource: String)

@Serializable
private data class ScaleResult(val scenario: ScaleScenario, val repetition: Int, val inputBytes: Long, val measurement: ScaleMeasurement)

@Serializable
private data class ScaleReport(
    val os: String, val architecture: String, val javaVersion: String, val processors: Int,
    val jarSha256: String, val fingerprintsSha256: String, val heap: String, val results: List<ScaleResult>,
)

@Serializable
private data class WindowsMeasurementRequest(val java: String, val arguments: List<String>, val output: String, val error: String, val metrics: String)

fun main(args: Array<String>) {
    require(args.size == 2 && args[0] in listOf("generate", "run")) { "Use generate|run INPUT_DIRECTORY" }
    val root = Path.of(args[1]).toAbsolutePath()
    if (args[0] == "generate") {
        generateScaleInputs(root)
        println("Generated ${scaleScenarios.size} synthetic capture pairs")
        return
    }
    val expected = json.decodeFromString<Map<String, String>>(Files.readString(root.resolve("fingerprints.json")))
    check(scaleFingerprints(root) == expected) { "Input fingerprints differ before execution" }
    val resultsDirectory = Path.of("build/scale-results").toAbsolutePath()
    Files.createDirectory(resultsDirectory)
    val jar = Path.of(System.getProperty("cli.jar")).toAbsolutePath()
    val results = mutableListOf<ScaleResult>()
    for (scenario in scaleScenarios) {
        val pair = root.resolve(scenario.name)
        val bytes = Files.walk(pair).use { paths -> paths.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
        repeat(if (scenario.expectedExit == 0) 3 else 1) { repetition ->
            val prefix = resultsDirectory.resolve("${scenario.name}-${repetition + 1}")
            val arguments = listOf("-Xms32m", "-Xmx512m", "-jar", jar.toString(), "inspect", "compare",
                "--before", pair.resolve("before").toString(), "--after", pair.resolve("after").toString(),
                "--limit", if (scenario.corruptLastPage) "1" else "1000", "--no-input")
            val measured = measureScaleProcess(arguments, prefix)
            check(measured.exit == scenario.expectedExit) { "${scenario.name}: expected exit ${scenario.expectedExit}, got ${measured.exit}" }
            verifyScaleOutput(scenario, Files.readString(Path.of("$prefix.stdout")))
            results += ScaleResult(scenario, repetition + 1, bytes, measured)
            println("${scenario.name} #${repetition + 1}: ${measured.elapsedMs} ms, ${measured.peakBytes} peak bytes, exit ${measured.exit}")
        }
    }
    check(scaleFingerprints(root) == expected) { "Inputs changed during execution" }
    Files.writeString(resultsDirectory.resolve("report.json"), json.encodeToString(ScaleReport(
        System.getProperty("os.name") + " " + System.getProperty("os.version"), System.getProperty("os.arch"),
        System.getProperty("java.runtime.version"), Runtime.getRuntime().availableProcessors(), scaleSha(jar),
        scaleSha(root.resolve("fingerprints.json")), "-Xms32m -Xmx512m", results)))
}

private fun measureScaleProcess(arguments: List<String>, prefix: Path): ScaleMeasurement {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val mac = System.getProperty("os.name").startsWith("Mac")
    val executable = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java").toString()
    val output = Path.of("$prefix.stdout")
    val error = Path.of("$prefix.stderr")
    val metrics = Path.of("$prefix.metrics")
    val command = if (windows) {
        val request = Path.of("$prefix.request.json")
        Files.writeString(request, json.encodeToString(WindowsMeasurementRequest(executable, arguments,
            output.toString(), error.toString(), metrics.toString())))
        listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", Path.of(".github/scripts/measure-windows-cli.ps1").toAbsolutePath().toString(), "-Request", request.toString())
    } else {
        check(mac || System.getProperty("os.name") == "Linux") { "Measurement supports Linux, macOS and Windows" }
        listOf("/usr/bin/time") + (if (mac) listOf("-l") else listOf("-f", "%M", "-o", metrics.toString())) + executable + arguments
    }
    val builder = ProcessBuilder(command)
    builder.environment().keys.removeIf { it.startsWith("SCOUTER") || it in setOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS") }
    builder.environment()["LC_ALL"] = "C"
    builder.redirectInput(ProcessBuilder.Redirect.from(Path.of(if (windows) "NUL" else "/dev/null").toFile()))
    builder.redirectOutput(Path.of(if (windows) "$prefix.wrapper.stdout" else output.toString()).toFile())
    builder.redirectError(Path.of(if (windows) "$prefix.wrapper.stderr" else error.toString()).toFile())
    val started = System.nanoTime()
    val process = builder.start()
    try {
        check(process.waitFor(130, TimeUnit.SECONDS)) { "CLI measurement exceeded 130 seconds: $prefix" }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        if (windows) {
            check(process.exitValue() == 0) { "Windows measurement failed; see $prefix.wrapper.stderr" }
            return json.decodeFromString<ScaleMeasurement>(Files.readString(metrics)).also { check(it.peakBytes > 0) }
        }
        val peak = if (mac) Regex("(?m)^\\s*(\\d+)\\s+maximum resident set size\\s*$")
            .find(Files.readString(error))!!.groupValues[1].toLong()
        else Files.readAllLines(metrics).last().trim().toLong() * 1024L
        check(peak > 0)
        return ScaleMeasurement(process.exitValue(), elapsed, peak, if (mac) "BSD time maximum RSS (bytes)" else "GNU time maximum RSS (KiB converted to bytes)")
    } finally {
        if (process.isAlive) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
