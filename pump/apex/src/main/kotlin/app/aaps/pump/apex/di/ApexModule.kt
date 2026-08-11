package app.aaps.pump.apex.di

import app.aaps.core.interfaces.di.PumpDriver
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.pump.apex.ApexPumpPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntKey
import dagger.multibindings.IntoMap

@Module(
    includes = [
        ApexUiModule::class,
        ApexServicesModule::class,
    ]
)
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class ApexModule {

    @Binds
    @PumpDriver
    @IntoMap
    @IntKey(1140)
    abstract fun bindApexPumpPlugin(plugin: ApexPumpPlugin): PluginBase
}
