package app.aaps.pump.medtrum.bench

import app.aaps.core.interfaces.queue.CustomCommand

data class MedtrumBenchRestartCommand(
    val queueWasSafe: Boolean,
    val bolusWasSafe: Boolean
) : CustomCommand {
    override val statusDescription: String = "MEDTRUM BENCH RESTART TEST"
}
