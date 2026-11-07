package dev.scouterctl.adapters.collector

import dev.scouterctl.core.ErrorCode
import dev.scouterctl.core.OperationFailure

internal val supportedCollectorVersions = setOf(
    "2.0.0", "2.0.1", "2.5.0", "2.5.1", "2.6.0", "2.6.1", "2.6.2", "2.7.0", "2.7.1",
    "2.8.1", "2.10.0", "2.10.2", "2.15.0", "2.17.1", "2.20.0", "2.21.1", "2.21.2", "2.21.3",
)

internal fun requireSupportedCollector(version: String) {
    if (version.substringBefore(' ') !in supportedCollectorVersions) {
        throw OperationFailure(ErrorCode.VERSION_MISMATCH,
            "Collector version is not supported; see docs/supported-versions.md")
    }
}
