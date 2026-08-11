package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.interfaces.ApexDeviceInfo

/** Get pump value.
 *
 * [value] - Value to get
 */
class GetValue(
    info: ApexDeviceInfo,
    val value: Value,
) : BaseValueCommand(info) {
    override val type = value.type
    override val valueId = value.valueId
    override val isWrite = false

    override val paddingValue: Int
        get() = when (value) {
            Value.LatestBoluses -> 0x01
            else -> 0xAA
        }

    enum class Value(val singleValueReturn: Boolean, val valueId: Int, val type: Int = 0x35) {
        /** Pump status and settings, proto <=4.10 */
        StatusV1(true, 0x00),

        /** Bolus history for month? It had returned A LOT of bolus entries */
        BolusHistory(false,0x01, 0x55),

        /** Pump alarms, returns latest 20 ones */
        Alarms(false, 0x03, 0x55),

        /** Latest TDDs */
        TDDs(false, 0x06, 0x55),

        /** Pump bolus wizard status */
        WizardStatus(true, 0x07),

        /** Pump basal profiles */
        BasalProfiles(false, 0x08),

        /** Pump status and settings, proto >=4.11 */
        StatusV2(true, 0x0c),

        /** Latest boluses */
        LatestBoluses(false, 0x21),

        /** Latest temporary basals, proto >=4.11 */
        LatestTemporaryBasals(false, 0x27),

        /** Firmware version, proto >=4.10 */
        Version(true, 0x31),
    }

    override fun toString(): String = "GetValue(${value.name})"
}