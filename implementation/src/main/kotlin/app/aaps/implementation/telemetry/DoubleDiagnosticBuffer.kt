package app.aaps.implementation.telemetry

/** Bounded swap on the caller: no disk waits. Two 128-record/64-KiB buffers at most. */
internal class DoubleDiagnosticBuffer(private val maxRecords: Int = 128, private val maxBytes: Int = 65536) {
    private val active = DiagnosticBatchBuffer(maxRecords, maxBytes)
    private var pending = emptyList<DiagnosticRecord>()
    private var losses: Loss? = null
    data class Loss(val count: Long, val firstUtc: Long, val lastUtc: Long, val types: Map<String, Long>)
    data class Offer(val accepted: Boolean, val pressure: Boolean)

    @Synchronized fun offer(record: DiagnosticRecord): Offer {
        if (active.offer(record)) return Offer(true, active.stats().records >= maxRecords || active.stats().bufferedBytes >= maxBytes - 4096)
        if (pending.isEmpty()) {
            pending = active.drain()
            if (active.offer(record)) return Offer(true, true)
        }
        val previous = losses
        losses = Loss((previous?.count ?: 0) + 1, previous?.firstUtc ?: record.utc, record.utc,
            previous?.types.orEmpty().toMutableMap().apply { put(record.type, (get(record.type) ?: 0) + 1) })
        return Offer(false, true)
    }

    @Synchronized fun drain(): List<DiagnosticRecord> = (pending + active.drain()).also { pending = emptyList() }
    @Synchronized fun stats() = active.stats()
    @Synchronized fun hasRecords() = pending.isNotEmpty() || active.stats().records > 0
    @Synchronized fun takeLoss(): Loss? = losses.also { losses = null }
    @Synchronized fun restoreLoss(loss: Loss) {
        val previous = losses
        losses = Loss(loss.count + (previous?.count ?: 0), minOf(loss.firstUtc, previous?.firstUtc ?: loss.firstUtc),
            maxOf(loss.lastUtc, previous?.lastUtc ?: loss.lastUtc), (loss.types.keys + previous?.types.orEmpty().keys).associateWith {
                (loss.types[it] ?: 0) + (previous?.types?.get(it) ?: 0)
            })
    }
}
