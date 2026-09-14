package app.aaps.core.data.diagnostics

import app.aaps.core.data.model.GV

enum class GlucoseChange { METADATA_ONLY, THERAPY_RELEVANT, UNKNOWN }

/** Only Nightscout identifiers and DB revision bookkeeping are excluded. Unknown fields remain compared. */
object GlucoseChangeClassifier {
    fun classify(previous: GV?, current: GV): GlucoseChange {
        if (previous == null || previous.id <= 0 || previous.id != current.id) return GlucoseChange.UNKNOWN
        val normalized = current.copy(
            version = previous.version, dateCreated = previous.dateCreated,
            ids = current.ids.copy(nightscoutId = previous.ids.nightscoutId, nightscoutSystemId = previous.ids.nightscoutSystemId)
        )
        return if (normalized == previous) GlucoseChange.METADATA_ONLY else GlucoseChange.THERAPY_RELEVANT
    }
}
