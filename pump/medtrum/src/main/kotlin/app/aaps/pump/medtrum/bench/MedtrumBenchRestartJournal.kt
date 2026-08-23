package app.aaps.pump.medtrum.bench

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedtrumBenchRestartJournal @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, "medtrum-bench-restart-journal.json")

    @Synchronized
    fun begin(campaignId: String) {
        val now = System.currentTimeMillis()
        write(
            campaignId = campaignId,
            state = BenchRestartState.PREFLIGHT,
            terminal = false,
            startedAt = now,
            lastWriteCommand = null,
            lastConfirmedDeviceState = null
        )
    }

    @Synchronized
    fun update(campaignId: String, state: BenchRestartState) = update(
        campaignId = campaignId,
        state = state,
        lastWriteCommand = null,
        lastConfirmedDeviceState = null
    )

    @Synchronized
    fun update(campaignId: String, event: BenchRestartEvent) = update(
        campaignId = campaignId,
        state = event.state,
        lastWriteCommand = event.name.takeIf { it in WRITE_EVENTS },
        lastConfirmedDeviceState = event.fields["realPumpState"]?.toString()
    )

    @Synchronized
    fun markInterruptedIfNeeded(): String? {
        val current = read() ?: return null
        if (current.optBoolean("terminal", true)) return null
        val campaignId = current.optString("campaignId")
        write(
            campaignId = campaignId,
            state = BenchRestartState.INTERRUPTED,
            terminal = true,
            startedAt = current.optLong("startedAt"),
            lastWriteCommand = current.nullableString("lastWriteCommand"),
            lastConfirmedDeviceState = current.nullableString("lastConfirmedDeviceState")
        )
        return campaignId
    }

    private fun read(): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun update(
        campaignId: String,
        state: BenchRestartState,
        lastWriteCommand: String?,
        lastConfirmedDeviceState: String?
    ) {
        val current = read()
        write(
            campaignId = campaignId,
            state = state,
            terminal = state in TERMINAL_STATES,
            startedAt = current?.optLong("startedAt")?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            lastWriteCommand = lastWriteCommand ?: current?.nullableString("lastWriteCommand"),
            lastConfirmedDeviceState = lastConfirmedDeviceState ?: current?.nullableString("lastConfirmedDeviceState")
        )
    }

    private fun write(
        campaignId: String,
        state: BenchRestartState,
        terminal: Boolean,
        startedAt: Long,
        lastWriteCommand: String?,
        lastConfirmedDeviceState: String?
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("schema", 1)
                .put("campaignId", campaignId)
                .put("state", state.name)
                .put("terminal", terminal)
                .put("lastWriteCommand", lastWriteCommand ?: JSONObject.NULL)
                .put("lastConfirmedDeviceState", lastConfirmedDeviceState ?: JSONObject.NULL)
                .put("startedAt", startedAt)
                .put("updatedAt", System.currentTimeMillis())
                .toString(2)
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    companion object {
        private val TERMINAL_STATES = setOf(BenchRestartState.COMPLETE, BenchRestartState.FAILED, BenchRestartState.BLOCKED, BenchRestartState.INTERRUPTED)
        private val WRITE_EVENTS = setOf(
            "bench_restart_settings_probe_tx",
            "bench_restart_settings_restore_tx",
            "bench_restart_transition_tx",
            "bench_restart_activate_tx"
        )
    }
}
