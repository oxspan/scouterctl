package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class CounterHistoryQueryDto(val date: String, val from: Long, val to: Long, val timezone: String,
    val objHash: Int, val counter: String)
@Serializable
data class CounterTargetDto(val objHash: Int, val objName: String, val objType: String)
@Serializable
data class CounterDefinitionDto(val name: String, val displayName: String?, val unit: String?)
@Serializable
data class CounterPointDto(val time: Long, val value: String?, val availability: String)

fun CounterHistoryQuery.toDto() = CounterHistoryQueryDto(date, from, to, timezone, objHash, counter)
fun CounterTarget.toDto() = CounterTargetDto(objHash, objName, objType)
fun CounterDefinition.toDto() = CounterDefinitionDto(name, displayName, unit)
fun CounterPoint.toDto() = CounterPointDto(time, value, availability.name.lowercase())
