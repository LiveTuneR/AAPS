package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.pump.apex.R
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.misc.BatteryType

enum class ApexStringKey(
    override val key: String,
    override val defaultValue: String,
    override val titleResId: Int = 0,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, Int> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {
    SerialNumber("apex_serial_number", "", R.string.setting_serial_number),
    LastConnectedSerialNumber("apex_last_connected_serial_number", "", exportable = false),
    BluetoothAddress("apex_bt_address", "", exportable = false),
    AlarmSoundLength(
        "apex_alarm_length",
        AlarmLength.Short.name,
        R.string.setting_alarm_length,
        PreferenceType.LIST,
        mapOf(
            AlarmLength.Short.name to R.string.setting_alarm_length_short,
            AlarmLength.Medium.name to R.string.setting_alarm_length_medium,
            AlarmLength.Long.name to R.string.setting_alarm_length_long
        )
    ),
    CalcBatteryType(
        "apex_battery_type",
        BatteryType.Custom.name,
        R.string.setting_calc_battery_type_title,
        PreferenceType.LIST,
        mapOf(
            BatteryType.Alkaline.name to R.string.setting_calc_battery_type_alkaline,
            BatteryType.Lithium.name to R.string.setting_calc_battery_type_lithium,
            BatteryType.NiMh.name to R.string.setting_calc_battery_type_ni_mh,
            BatteryType.NiZn.name to R.string.setting_calc_battery_type_ni_zn,
            BatteryType.Custom.name to R.string.setting_calc_battery_type_custom
        )
    ),
    FirmwareVer("apex_fw_ver", FirmwareVersion.AUTO.name, R.string.firmware_version, PreferenceType.LIST),
}
