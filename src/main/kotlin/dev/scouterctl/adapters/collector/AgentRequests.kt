package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import dev.scouterctl.application.agentSettingValueIsVisible
import dev.scouterctl.application.agentTextSettingValueIsVisible
import scouter.lang.pack.MapPack
import scouter.lang.pack.ObjectPack
import scouter.lang.pack.Pack
import scouter.lang.value.TextValue
import scouter.net.RequestCmd

internal class AgentRequests(private val exchange: (String, MapPack?) -> List<Pack>) {
    fun status(objHash: Int): AgentStatus {
        val agent = identity(objHash)
        if (!agent.alive) return AgentStatus(agent, AgentJvmAvailability.OFFLINE, null)
        val response = exchange(RequestCmd.OBJECT_ENV, MapPack().apply { put("objHash", objHash.toLong()) })
        if (response.isEmpty()) return AgentStatus(agent, AgentJvmAvailability.UNAVAILABLE, null)
        val pack = response.singleOrNull() as? MapPack
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected one Agent JVM response")
        fun text(key: String): String? {
            val value = pack.get(key) ?: return null
            return (value as? TextValue)?.value
                ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Agent JVM field")
        }
        val javaVersion = text("java.version")
            ?: return AgentStatus(agent, AgentJvmAvailability.UNAVAILABLE, null)
        if (javaVersion.isBlank()) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Agent Java version")
        return AgentStatus(agent, AgentJvmAvailability.AVAILABLE, AgentJvm(javaVersion, text("java.runtime.version"),
            text("java.vm.name"), text("java.vm.version"), text("java.vendor"), text("os.name"), text("os.arch")))
    }

    fun settings(objHash: Int): AgentConfiguration {
        val agent = identity(objHash)
        if (!agent.alive) throw OperationFailure(ErrorCode.NOT_FOUND, "Agent is not alive")
        val response = exchange(RequestCmd.LIST_CONFIGURE_WAS, MapPack().apply { put("objHash", objHash.toLong()) })
        if (response.isEmpty()) throw OperationFailure(ErrorCode.NOT_FOUND, "Agent settings are unavailable")
        return AgentConfiguration(agent, decodeRuntimeSettings(response, ::agentSettingValueIsVisible, ::agentTextSettingValueIsVisible))
    }

    fun activeRequests(objHash: Int, includeText: Boolean): ActiveRequests {
        val target = diagnosticAgent(objHash)
        return decodeActiveRequests(target, exchange(RequestCmd.OBJECT_ACTIVE_SERVICE_LIST,
            MapPack().apply { put("objHash", objHash.toLong()) }), includeText)
    }

    fun threadDump(objHash: Int): AgentThreadDump {
        val target = diagnosticAgent(objHash)
        return decodeThreadDump(target, exchange(RequestCmd.OBJECT_THREAD_DUMP,
            MapPack().apply { put("objHash", objHash.toLong()) }))
    }

    private fun diagnosticAgent(objHash: Int): AgentIdentity {
        if (objHash == 0) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Diagnostics require a nonzero --object-hash")
        return identity(objHash).also {
            if (!it.alive) throw OperationFailure(ErrorCode.NOT_FOUND, "Agent is offline; live diagnostics are unavailable")
        }
    }

    fun identity(objHash: Int): AgentIdentity {
        val agents = exchange(RequestCmd.OBJECT_LIST_REAL_TIME, null).map {
            it as? ObjectPack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected object pack")
        }.filter { it.objHash == objHash }
        if (agents.size > 1) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Duplicate Agent identity")
        val agent = agents.singleOrNull() ?: throw OperationFailure(ErrorCode.NOT_FOUND, "Agent was not found")
        return AgentIdentity(agent.objHash, agent.objName ?: "", agent.objType ?: "", agent.alive, agent.version ?: "unknown")
    }
}
