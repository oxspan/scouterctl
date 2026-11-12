package dev.scouterctl.application

import dev.scouterctl.core.*

interface ObservationSource : AutoCloseable {
    val info: ServerInfo
    fun timezone(): CollectorTimezone?
    fun status(): CollectorStatus
    fun settings(): List<RuntimeSetting>
    fun agentStatus(objHash: Int): AgentStatus
    fun agentSettings(objHash: Int): AgentConfiguration
    fun counterHistory(query: CounterHistoryQuery): CounterHistory
    fun realtimeCounters(objHash: Int): RealtimeCounters
    fun objects(date: String? = null): List<ObjectRecord>
    fun page(query: Query, cursor: Cursor, includeText: Boolean = false): XLogPage
    fun profile(date: String, txid: Long, includeText: Boolean = false, includeStepDetails: Boolean = false): ProfileRecord
}

interface CaptureRepository {
    fun create()
    fun load(): CaptureManifest
    fun save(manifest: CaptureManifest)
    fun writePage(index: Int, records: List<XLogRecord>): PageFile
    fun validate(manifest: CaptureManifest)
}

interface EvidenceRepository {
    fun create()
    fun load(): EvidenceManifest
    fun save(manifest: EvidenceManifest)
    fun writePage(index: Int, records: List<EvidenceRecord>): EvidencePage
    fun readPage(manifest: EvidenceManifest, index: Int): List<EvidenceRecord>
    fun validate(manifest: EvidenceManifest)
    fun forEachRecord(manifest: EvidenceManifest, consume: (EvidenceRecord) -> Unit)
}

interface ContextRepository {
    fun read(): ContextConfig
    fun update(change: (ContextConfig) -> ContextConfig): ContextConfig
}

interface CounterCaptureRepository : AutoCloseable {
    fun create()
    fun load(): CounterCaptureManifest
    fun save(manifest: CounterCaptureManifest)
    fun writeChunk(index: Int, points: List<CounterPoint>): CounterChunkFile
    fun validate(manifest: CounterCaptureManifest)
}
