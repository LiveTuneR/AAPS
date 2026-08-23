package app.aaps.pump.medtrum.comm.packets

import dagger.android.HasAndroidInjector
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.comm.enums.CommandType.SET_PATCH
import app.aaps.pump.medtrum.extension.toByte
import app.aaps.pump.medtrum.extension.toByteArray
import javax.inject.Inject
import kotlin.math.round

class SetPatchPacket(
    injector: HasAndroidInjector,
    private val configuration: Configuration? = null
) : MedtrumPacket(injector) {

    @Inject lateinit var medtrumPump: MedtrumPump

    init {
        opCode = SET_PATCH.code
    }

    override fun getRequest(): ByteArray {
        /**
         * byte 0: opCode
         * byte 1: alarmSetting                    // See AlarmSetting
         * byte 2-3: hourlyMaxInsulin              // Max hourly dose of insulin, divided by 0.05
         * byte 4-5: dailyMaxSet                   // Max daily dose of insulin, divided by 0.05
         * byte 6: expirationTimer                 // Expiration timer, 0 = no expiration 1 = 12 hour reminder and expiration
         * byte 7: autoSuspendEnable               // Value for auto mode, not used for AAPS
         * byte 8: autoSuspendTime                 // Value for auto mode, not used for AAPS
         * byte 9: lowSuspend                      // Value for auto mode, not used for AAPS
         * byte 10: predictiveLowSuspend           // Value for auto mode, not used for AAPS
         * byte 11: predictiveLowSuspendRange      // Value for auto mode, not used for AAPS
         */

        val values = configuration ?: Configuration(
            alarmSetting = medtrumPump.desiredAlarmSetting,
            hourlyMaxInsulin = medtrumPump.desiredHourlyMaxInsulin,
            dailyMaxInsulin = medtrumPump.desiredDailyMaxInsulin,
            patchExpiration = medtrumPump.desiredPatchExpiration
        )
        val hourlyMaxInsulin = round(values.hourlyMaxInsulin / 0.05).toInt()
        val dailyMaxInsulin = round(values.dailyMaxInsulin / 0.05).toInt()

        return byteArrayOf(opCode) + values.alarmSetting.code + hourlyMaxInsulin.toByteArray(2) + dailyMaxInsulin.toByteArray(2) + values.patchExpiration.toByte() +
            values.autoSuspendEnable + values.autoSuspendTime + values.lowSuspend + values.predictiveLowSuspend + values.predictiveLowSuspendRange
    }

    data class Configuration(
        val alarmSetting: AlarmSetting,
        val hourlyMaxInsulin: Int,
        val dailyMaxInsulin: Int,
        val patchExpiration: Boolean,
        val autoSuspendEnable: Byte = 0,
        val autoSuspendTime: Byte = 12,
        val lowSuspend: Byte = 0,
        val predictiveLowSuspend: Byte = 0,
        val predictiveLowSuspendRange: Byte = 30
    )
}
