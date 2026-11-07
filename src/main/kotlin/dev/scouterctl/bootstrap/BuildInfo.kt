package dev.scouterctl.bootstrap

import java.util.Properties

object BuildInfo {
    val version: String = checkNotNull(BuildInfo::class.java.getResourceAsStream("/scouterctl-version.properties"))
        .use { stream -> checkNotNull(Properties().apply { load(stream) }.getProperty("version")) }
}
