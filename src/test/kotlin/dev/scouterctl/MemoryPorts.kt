package dev.scouterctl

import dev.scouterctl.application.*
import dev.scouterctl.core.*

internal val portSource = ServerInfo("memory", "2.21.3", "UTC", "test")
internal val portQuery = Query("20260101", 1767225600000, 1767225660000, "UTC", 1, 2)
internal fun portRow(id: Long, elapsed: Int = 1) = XLogRecord(id.toString(), "0", "0", portQuery.from + id, 1, 7, elapsed, 0)

internal class MemoryEvidence : EvidenceRepository {
    var manifest: EvidenceManifest? = null
    val records = mutableMapOf<Int, List<EvidenceRecord>>()
    var rejectSave = false
    var brokenPage: Int? = null

    override fun create() { check(manifest == null) }
    override fun load(): EvidenceManifest = checkNotNull(manifest)
    override fun save(manifest: EvidenceManifest) {
        check(!rejectSave) { "Unexpected checkpoint write" }
        manifest.pages.forEachIndexed { index, page -> check(records[index]?.size == page.records) { "Checkpoint references unwritten page" } }
        this.manifest = manifest
    }
    override fun writePage(index: Int, records: List<EvidenceRecord>): EvidencePage {
        this.records[index] = records.toList()
        return EvidencePage("page-$index", "memory", records.size, records.count { it.profileStatus == ProfileAvailability.UNAVAILABLE })
    }
    override fun readPage(manifest: EvidenceManifest, index: Int): List<EvidenceRecord> {
        if (index == brokenPage) throw OperationFailure(ErrorCode.IO_ERROR, "broken late page")
        return checkNotNull(records[index])
    }
    override fun validate(manifest: EvidenceManifest) = forEachRecord(manifest) { }
    override fun forEachRecord(manifest: EvidenceManifest, consume: (EvidenceRecord) -> Unit) {
        manifest.pages.indices.forEach { index -> readPage(manifest, index).forEach(consume) }
    }
}

internal class MemorySource(
    private val fetch: (Cursor) -> XLogPage,
    private val profile: (Long) -> ProfileRecord = { ProfileRecord(it.toString(), emptyList()) },
) : ObservationSource {
    override val info = portSource
    var closed = false
    override fun status() = CollectorStatus(1, 2, 3)
    override fun settings(): List<RuntimeSetting> = emptyList()
    override fun agentStatus(objHash: Int): AgentStatus = throw OperationFailure(ErrorCode.NOT_FOUND, "No Agent in memory fixture")
    override fun agentSettings(objHash: Int): AgentConfiguration = throw OperationFailure(ErrorCode.NOT_FOUND, "No Agent in memory fixture")
    override fun objects(): List<ObjectRecord> = emptyList()
    override fun page(query: Query, cursor: Cursor) = fetch(cursor)
    override fun profile(date: String, txid: Long, includeText: Boolean) = profile(txid)
    override fun close() { closed = true }
}

internal class MemoryContexts(var config: ContextConfig = ContextConfig()) : ContextRepository {
    override fun read() = config
    override fun update(change: (ContextConfig) -> ContextConfig): ContextConfig = change(config).also { config = it }
}
