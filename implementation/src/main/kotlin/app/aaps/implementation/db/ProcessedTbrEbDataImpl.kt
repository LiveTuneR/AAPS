package app.aaps.implementation.db

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.objects.extensions.toTemporaryBasal
import dagger.Reusable
import javax.inject.Inject

@Reusable
class ProcessedTbrEbDataImpl @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction
) : ProcessedTbrEbData {

    private suspend fun getConvertedExtended(timestamp: Long): TB? {
        if (activePlugin.activePump.isFakingTempsByExtendedBoluses) {
            val eb = persistenceLayer.getExtendedBolusActiveAt(timestamp)
            val profile = profileFunction.getProfile(timestamp) ?: return null
            return eb?.toTemporaryBasal(profile)
        }
        return null
    }

    override suspend fun getTempBasalIncludingConvertedExtended(timestamp: Long): TB? {
        app.aaps.core.data.diagnostics.EnergyRuntimeCounters.add("db.tbr.point")
        return persistenceLayer.getTemporaryBasalActiveAt(timestamp) ?: getConvertedExtended(timestamp)
    }

    override suspend fun getTempBasalsIncludingConvertedExtended(startTime: Long, endTime: Long): ProcessedTbrEbData.TempBasalsInRange {
        app.aaps.core.data.diagnostics.EnergyRuntimeCounters.add("db.tbr.range")
        val basals = persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(startTime, endTime).map { it.copy() }.sortedByDescending { it.timestamp }
        val extended = if (activePlugin.activePump.isFakingTempsByExtendedBoluses)
            persistenceLayer.getExtendedBolusesActiveBetweenTimeAndTime(startTime, endTime).map { it.copy() }.sortedByDescending { it.timestamp }
        else emptyList()
        return object : ProcessedTbrEbData.TempBasalsInRange {
            override suspend fun at(timestamp: Long): TB? {
                require(timestamp in startTime..endTime)
                basals.firstOrNull { it.timestamp <= timestamp && it.end > timestamp }?.let { return it.copy() }
                val eb = extended.firstOrNull { it.timestamp <= timestamp && it.end > timestamp } ?: return null
                val profile = profileFunction.getProfile(timestamp) ?: return null
                return eb.toTemporaryBasal(profile)
            }
        }
    }
}
