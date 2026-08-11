package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.interfaces.ApexDeviceInfo

/** Set basal profile [index] to be used now.
 *
 * * [index] - Basal profile index
 */
class UpdateUsedBasalProfile(
    info: ApexDeviceInfo,
    val index: Int,
    private val useProto411Format: Boolean,
) : BaseValueCommand(info) {
    override val valueId = if (useProto411Format) 0x34 else 0x04
    override val isWrite = true
    override val canBeMerged = true

    override val additionalData: ByteArray
        get() = byteArrayOf(index.toByte())

    override fun toString(): String = "UpdateUsedBasalProfile($index, valueId=0x${valueId.toString(16)})"
}
