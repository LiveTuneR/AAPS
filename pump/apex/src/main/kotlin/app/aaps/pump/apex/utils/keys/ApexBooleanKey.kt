package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.pump.apex.R

enum class ApexBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val exportable: Boolean = true,
    override val hideParentScreenIfHidden: Boolean = false
) : BooleanPreferenceKey {
    LogInsulinChange("apex_log_insulin_change", true, R.string.setting_log_insulin_change, defaultedBySM = true),
    LogBatteryChange("apex_log_battery_change", true, R.string.setting_log_battery_change, defaultedBySM = true),
    CalculateBatteryPercentage(
        "apex_calc_battery_percentage",
        true,
        R.string.setting_calc_battery_title,
        R.string.setting_calc_battery_summary,
        defaultedBySM = true
    ),
    HideSerial("apex_hide_serial", true, R.string.setting_hide_serial, defaultedBySM = true),
    EnableExperimentalControl(
        "apex_enable_experimental_control",
        false,
        R.string.setting_enable_experimental_control,
        R.string.setting_enable_experimental_control_summary,
        engineeringModeOnly = true,
    ),
}
