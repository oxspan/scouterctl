package dev.scouterctl

import dev.scouterctl.bootstrap.BuildInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CliCoverageTest : FunSpec({
    if (System.getProperty("coverage.agent") != null) {
        test("CLI processes write separate coverage files without changing version output or stderr") {
            val destinations = (1..2).map {
                val command = cliJavaCommand()
                val agent = command.single { it.startsWith("-javaagent:") }
                val destination = Path.of(agent.substringAfter("destfile=").substringBefore(','))
                val process = ProcessBuilder(command + "--version").start()
                try {
                    process.waitFor(15, TimeUnit.SECONDS) shouldBe true
                    process.exitValue() shouldBe 0
                    process.inputStream.bufferedReader().readText().trim() shouldBe "scouterctl ${BuildInfo.version}"
                    process.errorStream.bufferedReader().readText() shouldBe ""
                    (Files.size(destination) > 0) shouldBe true
                    destination
                } finally { process.destroyForcibly() }
            }
            destinations.distinct().size shouldBe 2
        }
    }
})
