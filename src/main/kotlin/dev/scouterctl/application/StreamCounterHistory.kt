package dev.scouterctl.application

import dev.scouterctl.core.*

sealed interface CounterHistoryEvent {
    data class Start(val source: ServerInfo, val query: CounterHistoryQuery,
        val target: CounterTarget, val counter: CounterDefinition) : CounterHistoryEvent
    data class Record(val point: CounterPoint) : CounterHistoryEvent
    data class Complete(val records: Int) : CounterHistoryEvent
}

fun streamCounterHistory(source: ObservationSource, query: CounterHistoryQuery, consume: (CounterHistoryEvent) -> Unit) {
    query.validate()
    val history = source.counterHistory(query)
    consume(CounterHistoryEvent.Start(source.info, query, history.target, history.counter))
    history.points.forEach { consume(CounterHistoryEvent.Record(it)) }
    consume(CounterHistoryEvent.Complete(history.points.size))
}
