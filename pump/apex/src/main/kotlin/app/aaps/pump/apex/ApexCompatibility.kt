package app.aaps.pump.apex

import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.commands.pump.Version

object ApexCompatibility {
    private val legacyMatrix = setOf(
        FirmwareProtocol(6, 24, 4, 9),
        FirmwareProtocol(6, 25, 4, 10),
        FirmwareProtocol(6, 27, 4, 11),
        FirmwareProtocol(6, 28, 4, 11),
    )

    fun isKnownLegacyVersion(version: Version): Boolean = isKnownLegacyVersion(
        version.firmwareMajor,
        version.firmwareMinor,
        version.protocolMajor,
        version.protocolMinor,
    )

    fun isControlEligible(version: Version?, selectedVersion: FirmwareVersion, enabled: Boolean): Boolean =
        enabled && selectedVersion == FirmwareVersion.AUTO && version?.let(::isKnownLegacyVersion) == true

    internal fun isKnownLegacyVersion(
        firmwareMajor: Int,
        firmwareMinor: Int,
        protocolMajor: Int,
        protocolMinor: Int,
    ): Boolean = FirmwareProtocol(firmwareMajor, firmwareMinor, protocolMajor, protocolMinor) in legacyMatrix

    private data class FirmwareProtocol(
        val firmwareMajor: Int,
        val firmwareMinor: Int,
        val protocolMajor: Int,
        val protocolMinor: Int,
    )
}
