package app.aaps.core.interfaces.db

import app.aaps.core.data.model.TB

interface ProcessedTbrEbData {

    /**
     * Get running temporary basal at time
     *
     *  @return     running temporary basal or null if no tbr is running
     *              If pump is faking extended boluses as temporary basals
     *              return extended converted to temporary basal with type == FAKE_EXTENDED
     */
    suspend fun getTempBasalIncludingConvertedExtended(timestamp: Long): TB?

    interface TempBasalsInRange { suspend fun at(timestamp: Long): TB? }
    /** Same real-TBR-before-faked-EB precedence as the point lookup. */
    suspend fun getTempBasalsIncludingConvertedExtended(startTime: Long, endTime: Long): TempBasalsInRange

}