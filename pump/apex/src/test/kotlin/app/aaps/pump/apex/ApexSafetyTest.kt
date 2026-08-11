package app.aaps.pump.apex

import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.diagnostics.ApexTraceSanitizer
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ApexSafetyTest {

    @Test
    fun `experimental control opt in is visible but disabled by default`() {
        assertThat(ApexBooleanKey.EnableExperimentalControl.defaultValue).isFalse()
        assertThat(ApexBooleanKey.EnableExperimentalControl.engineeringModeOnly).isFalse()
    }

    @Test
    fun `only observed legacy firmware protocol pairs are accepted`() {
        assertThat(ApexCompatibility.isKnownLegacyVersion(6, 24, 4, 9)).isTrue()
        assertThat(ApexCompatibility.isKnownLegacyVersion(6, 25, 4, 10)).isTrue()
        assertThat(ApexCompatibility.isKnownLegacyVersion(6, 27, 4, 11)).isTrue()
        assertThat(ApexCompatibility.isKnownLegacyVersion(6, 28, 4, 11)).isTrue()
        assertThat(ApexCompatibility.isKnownLegacyVersion(1, 1, 4, 12)).isTrue()

        assertThat(ApexCompatibility.isKnownLegacyVersion(6, 29, 4, 11)).isFalse()
        assertThat(ApexCompatibility.isKnownLegacyVersion(6, 28, 4, 12)).isFalse()
        assertThat(ApexCompatibility.isKnownLegacyVersion(1, 1, 4, 11)).isFalse()
        assertThat(ApexCompatibility.isKnownLegacyVersion(1, 2, 4, 12)).isFalse()
        assertThat(ApexCompatibility.isKnownLegacyVersion(7, 0, 5, 0)).isFalse()
    }

    @Test
    fun `reconnect backoff is bounded`() {
        assertThat((1..8).map(ApexCommDirector::reconnectDelayMs))
            .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L, 16_000L, 16_000L)
            .inOrder()
    }

    @Test
    fun `therapy control requires enabled automatic observed version`() {
        val observed = Version(6, 28, ProtocolVersion.PROTO_4_11)

        assertThat(ApexCompatibility.isControlEligible(observed, FirmwareVersion.AUTO, true)).isTrue()
        assertThat(ApexCompatibility.isControlEligible(observed, FirmwareVersion.FW_6_28, true)).isFalse()
        assertThat(ApexCompatibility.isControlEligible(observed, FirmwareVersion.AUTO, false)).isFalse()
        assertThat(ApexCompatibility.isControlEligible(null, FirmwareVersion.AUTO, true)).isFalse()
    }

    @Test
    fun `firmware 1110 compatibility workaround is exact`() {
        assertThat(ApexCompatibility.isFirmware11Protocol412(Version(1, 1, ProtocolVersion.PROTO_4_12))).isTrue()
        assertThat(ApexCompatibility.isFirmware11Protocol412(Version(1, 1, ProtocolVersion.PROTO_4_11))).isFalse()
        assertThat(ApexCompatibility.isFirmware11Protocol412(Version(6, 28, ProtocolVersion.PROTO_4_12))).isFalse()
    }

    @Test
    fun `diagnostics hash identifiers and truncate ordinary values`() {
        val serial = ApexTraceSanitizer.sanitize("pumpSerial", "12345678", 160)
        val address = ApexTraceSanitizer.sanitize("macAddress", "AA:BB:CC:DD:EE:FF", 160)
        val ordinary = ApexTraceSanitizer.sanitize("reason", "abcdefgh", 4)

        assertThat(serial).isEqualTo(ApexTraceSanitizer.anonymize("12345678"))
        assertThat(address).isEqualTo(ApexTraceSanitizer.anonymize("AA:BB:CC:DD:EE:FF"))
        assertThat(ordinary).isEqualTo("abcd")
    }
}
