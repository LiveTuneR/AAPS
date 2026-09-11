package app.aaps.implementation.maintenance

import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MaintenanceImplTest : TestBaseWithProfile() {

    @Mock lateinit var nsSettingsStatus: NSSettingsStatus
    @Mock lateinit var loggerUtils: LoggerUtils
    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var cloudStorageManager: CloudStorageManager

    private lateinit var sut: MaintenanceImpl
    @TempDir lateinit var logDirectory: File

    @BeforeEach
    fun mock() {
        sut = MaintenanceImpl(context, rh, preferences, nsSettingsStatus, aapsLogger, config, fileListProvider, loggerUtils, cloudStorageManager)
        whenever(loggerUtils.suffix).thenReturn(".log.zip")
        whenever(loggerUtils.logDirectory).thenReturn("src/test/assets/logger")
    }

    @Test fun logFilesTest() {
        var logs = sut.getLogFiles(2)
        assertThat(logs.map { it.name }).containsExactly(
            "AndroidAPS.log",
            "AndroidAPS.2018-01-03_01-01-00.1.zip",
        ).inOrder()
        logs = sut.getLogFiles(10)
        assertThat(logs).hasSize(4)
    }

    @Test fun `zero and one selection and retention have exact count semantics`() {
        whenever(loggerUtils.logDirectory).thenReturn(logDirectory.path)
        val active = File(logDirectory, "AndroidAPS.log").apply { writeText("active") }
        val old = File(logDirectory, "AndroidAPS._2026-09-11_00-00-00_.99.zip").apply { writeText("old") }
        val newest = File(logDirectory, "AndroidAPS._2026-09-11_00-00-00_.100.zip").apply { writeText("new") }
        assertThat(sut.getLogFiles(0)).isEmpty()
        assertThat(sut.getLogFiles(-1)).isEmpty()
        assertThat(sut.getLogFiles(1)).containsExactly(active)
        sut.deleteLogs(1)
        assertThat(old.exists()).isFalse()
        assertThat(newest.exists()).isTrue()
        assertThat(active.exists()).isTrue()
        sut.deleteLogs(0)
        assertThat(newest.exists()).isFalse()
        assertThat(active.exists()).isTrue()
    }
}
