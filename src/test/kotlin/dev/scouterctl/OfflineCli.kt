package dev.scouterctl

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun runOfflineCli(work: Path, args: List<String>): Outcome {
    val output = Files.createTempFile(work, "inspect-", ".jsonl")
    val stderr = Files.createTempFile(work, "inspect-", ".stderr")
    val child = ProcessBuilder(cliJavaCommand() + args).apply {
        environment().remove("SCOUTERCTL_PASSWORD")
    }.redirectOutput(output.toFile()).redirectError(stderr.toFile()).start()
    try {
        check(child.waitFor(20, TimeUnit.SECONDS)) { "Offline CLI did not terminate" }
        return Outcome(child.exitValue(), Files.readString(output), Files.readString(stderr))
    } finally { child.destroyForcibly() }
}
