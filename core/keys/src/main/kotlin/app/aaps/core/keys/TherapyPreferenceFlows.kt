package app.aaps.core.keys

import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.flow.StateFlow

/** Typed therapy namespaces only. Remote services, identity, credentials and arbitrary preferences are excluded. */
object TherapyPreferenceFlows {
    private fun therapy(name: String) = listOf("Aps","Autosens","Absorption","Insulin","Safety","Loop").any(name::startsWith)

    fun observe(preferences: Preferences): Map<String,StateFlow<Any>> = buildMap {
        BooleanKey.entries.filter { therapy(it.name) }.forEach { put(it.name,preferences.observe(it)) }
        IntKey.entries.filter { therapy(it.name) }.forEach { put(it.name,preferences.observe(it)) }
        DoubleKey.entries.filter { therapy(it.name) }.forEach { put(it.name,preferences.observe(it)) }
        UnitDoubleKey.entries.filter { therapy(it.name) }.forEach { put(it.name,preferences.observe(it)) }
        listOf(StringKey.SafetyAge,StringKey.GeneralUnits).forEach { put(it.name,preferences.observe(it)) }
    }
}
