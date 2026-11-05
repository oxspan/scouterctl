package dev.scouterctl.core

data class PageFile(val name: String, val sha256: String, val records: Int)

data class CaptureManifest(
    val schemaVersion: String = "scouterctl.capture/v1",
    val source: ServerInfo,
    val endpoint: String,
    val query: Query,
    val cursor: Cursor = Cursor(),
    val executionComplete: Boolean = false,
    val upstreamCoverage: String = "unknown",
    val sampling: String = "unknown",
    val timezoneVerification: String = "operator-declared",
    val files: List<PageFile> = emptyList(),
)
