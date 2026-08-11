package app.aaps.pump.apex.di

import app.aaps.pump.apex.ApexDriverStatus
import app.aaps.pump.apex.ApexService
import app.aaps.pump.apex.connectivity.bluetooth.ApexBLE
import app.aaps.pump.apex.connectivity.bluetooth.ApexTransport
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.misc.ApexDeviceInfoImpl
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class ApexServicesModule {
    @Binds abstract fun contributesApexDeviceInfo(apexDeviceInfoImpl: ApexDeviceInfoImpl): ApexDeviceInfo
    @Binds abstract fun bindApexTransport(apexBLE: ApexBLE): ApexTransport
    @ContributesAndroidInjector abstract fun contributesApexDriverStatus(): ApexDriverStatus
    @ContributesAndroidInjector abstract fun contributesApexBluetooth(): ApexBLE
    @ContributesAndroidInjector abstract fun contributesApexService(): ApexService
}
