package dev.scouterctl

import java.nio.file.Path
import java.util.UUID

/** Explicit JVM arguments keep coverage instrumentation out of stdout/stderr. */
internal fun cliCoverageOptions(): List<String> {
    val agent = System.getProperty("coverage.agent") ?: return emptyList()
    val directory = checkNotNull(System.getProperty("coverage.directory"))
    val destination = Path.of(directory, "cli-${UUID.randomUUID()}.exec")
    return listOf("-javaagent:$agent=destfile=$destination,append=false,includes=dev.scouterctl.*")
}

internal fun cliJavaCommand(
    jar: String = System.getProperty("cli.jar"),
    jvmOptions: List<String> = emptyList(),
): List<String> = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) +
    cliCoverageOptions() + jvmOptions + listOf("-jar", jar)

/** The Gradle-generated command launcher reads JAVA_OPTS without printing it. */
internal fun MutableMap<String, String>.withCliCoverage() {
    val options = cliCoverageOptions()
    if (options.isNotEmpty()) {
        this["JAVA_OPTS"] = listOfNotNull(this["JAVA_OPTS"], options.joinToString(" ") { "\"$it\"" }).joinToString(" ")
    }
}
