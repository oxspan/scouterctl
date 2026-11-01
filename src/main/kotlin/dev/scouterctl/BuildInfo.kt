package dev.scouterctl

import picocli.CommandLine
import java.util.Properties

object BuildInfo {
    val version: String = checkNotNull(BuildInfo::class.java.getResourceAsStream("/scouterctl-version.properties"))
        .use { stream -> checkNotNull(Properties().apply { load(stream) }.getProperty("version")) }
}

class CliVersion : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("scouterctl ${BuildInfo.version}")
}
