package app.aaps.plugins.main.general.persistentNotification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.extensions.apsAdjustedTargetMgdl
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.core.utils.DeferredForegroundStart
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("PrivatePropertyName", "DEPRECATION")
@Singleton
class PersistentNotificationPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val activePlugins: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val rxBus: RxBus,
    private val context: Context,
    private val notificationHolder: NotificationHolder,
    private val dummyServiceHelper: DummyServiceHelper,
    private val iconsProvider: IconsProvider,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val config: Config,
    private val decimalFormatter: DecimalFormatter,
    private val ch: ConcentrationHelper,
    private val loop: Loop,
    private val persistenceLayer: PersistenceLayer,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val dateUtil: DateUtil,
    private val trendCalculator: TrendCalculator
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.ongoingnotificaction)
        .enableByDefault(true)
        .alwaysEnabled(true)
        .showInList { false }
        .description(R.string.description_persistent_notification),
    aapsLogger, rh
) {

    // For Android Auto
    // Intents are not declared in manifest and not consumed, this is intentionally because actually we can't do anything with
    private val PACKAGE = "info.nightscout"
    private val READ_ACTION = "info.nightscout.androidaps.ACTION_MESSAGE_READ"
    private val REPLY_ACTION = "info.nightscout.androidaps.ACTION_MESSAGE_REPLY"
    private val CONVERSATION_ID = "conversation_id"
    private val EXTRA_VOICE_REPLY = "extra_voice_reply"
    // End Android auto

    private val disposable = CompositeDisposable()
    private val deferredStart = DeferredForegroundStart()
    private var lastAutoNotificationContent: String = ""
    private var lastAutoNotificationAt = Long.MIN_VALUE
    private var notificationScope: CoroutineScope? = null
    private var refreshRequests: Channel<Unit>? = null
    private val autoRequested = AtomicBoolean(false)
    @Inject lateinit var presentationKey: app.aaps.core.objects.PresentationRefreshKey
    private val buildGate = app.aaps.core.data.diagnostics.PresentationBuildGate()
    private var lastBuildIncludedAuto = false

    override suspend fun onStart() {
        super.onStart()
        notificationHolder.createNotificationChannel()
        buildGate.reset()
        lastBuildIncludedAuto = false
        val requests = Channel<Unit>(Channel.CONFLATED)
        refreshRequests = requests
        notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope ->
            scope.launch {
                for (request in requests) {
                    try {
                        updateNotification(autoRequested.getAndSet(false))
                        deferredStart.start { dummyServiceHelper.startService(context) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { fabricPrivacy.logException(error) }
                }
            }
        }
        disposable += Observable.merge(
            rxBus.toObservable(EventRefreshOverview::class.java).map { Unit },
            rxBus.toObservable(EventAutosensCalculationFinished::class.java).map { Unit }
        )
            .throttleLatest(500, TimeUnit.MILLISECONDS, aapsSchedulers.io, true)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate(includeAuto = true) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        triggerNotificationUpdate()
    }

    override suspend fun onStop() {
        disposable.clear()
        refreshRequests?.close()
        refreshRequests = null
        notificationScope?.cancel()
        notificationScope = null
        autoRequested.set(false)
        deferredStart.cancel()
        dummyServiceHelper.stopService(context)
        super.onStop()
    }

    private fun triggerNotificationUpdate(includeAuto: Boolean = false) {
        if (includeAuto) autoRequested.set(true)
        refreshRequests?.trySend(Unit)
    }

    private suspend fun updateNotification(includeAuto: Boolean = false) {
        if (!config.appInitialized) return
        val refreshKey = if (::presentationKey.isInitialized) presentationKey.current(dateUtil.now()) else null
        if (!buildGate.needsBuild(refreshKey) && (!includeAuto || lastBuildIncludedAuto)) {
            app.aaps.core.data.diagnostics.EnergyRuntimeCounters.add("notification.skippedUnchanged"); return
        }
        app.aaps.core.data.diagnostics.EnergyRuntimeCounters.add("notification.builds")
        val pump = activePlugins.activePump
        var line1: String?
        var line2: String? = null
        var line3: String? = null
        var unreadConversationBuilder: NotificationCompat.CarExtender.UnreadConversation.Builder? = null
        if (profileFunction.isProfileValid("Notification")) {
            val lastBG = iobCobCalculator.ads.lastBg()
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData
            if (lastBG != null) {
                val trendSymbol = (trendCalculator.getTrendArrow(iobCobCalculator.ads)
                    ?.takeIf { it != TrendArrow.NONE } ?: TrendArrow.FLAT).symbol
                line1 = profileUtil.fromMgdlToStringInUnits(lastBG.recalculated) + " " + trendSymbol
                if (glucoseStatus != null) {
                    line1 += " " + profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                } else {
                    line1 += " " + rh.gs(R.string.old_data)
                }
            } else {
                line1 = rh.gs(app.aaps.core.ui.R.string.missed_bg_readings)
            }
            val activeTemp = processedTbrEbData.getTempBasalIncludingConvertedExtended(System.currentTimeMillis())
            line1 += if (activeTemp != null) {
                " • " + activeTemp.toStringShort(rh) + " "
            } else {
                " • " + rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, ch.fromPump(pump.baseBasalRate)) + " "
            }
            val profileName = profileFunction.getProfileName()
            //IOB
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val cobInfo = iobCobCalculator.getCobInfo("PersistentNotificationPlugin")
            line2 =
                rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " + rh.gs(R.string.notification_iob_short, bolusIob.iob + basalIob.basaliob) + " • " + rh.gs(
                    app.aaps.core.ui.R
                        .string.cob
                ) + ": " + cobInfo.generateCOBString(decimalFormatter)
            line3 = profileName
            /// For Android Auto
            val msgReadIntent = Intent()
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .setAction(READ_ACTION)
                .putExtra(CONVERSATION_ID, notificationHolder.notificationID)
                .setPackage(PACKAGE)
            val msgReadPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationHolder.notificationID,
                msgReadIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val msgReplyIntent = Intent()
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .setAction(REPLY_ACTION)
                .putExtra(CONVERSATION_ID, notificationHolder.notificationID)
                .setPackage(PACKAGE)
            val msgReplyPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationHolder.notificationID,
                msgReplyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // Build a RemoteInput for receiving voice input from devices
            val remoteInput = RemoteInput.Builder(EXTRA_VOICE_REPLY).build()
            // Build Android Auto message: IOB • COB • Target • Profile
            val units = profileFunction.getUnits()
            var aaTarget = ""
            val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
            if (tempTarget != null) {
                aaTarget = profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) +
                    " " + dateUtil.untilString(tempTarget.end, rh)
            } else {
                profileFunction.getProfile()?.let { profile ->
                    val adjustedTarget = profile.apsAdjustedTargetMgdl(loop, config, processedDeviceStatusData)
                    aaTarget = if (adjustedTarget != null) {
                        profileUtil.toTargetRangeString(adjustedTarget, adjustedTarget, GlucoseUnit.MGDL, units)
                    } else {
                        profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
                    }
                }
            }
            val aaMsg = decimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob) + rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname) +
                " • " + cobInfo.generateCOBString(decimalFormatter) +
                " • " + aaTarget +
                " • " + profileName
            unreadConversationBuilder = NotificationCompat.CarExtender.UnreadConversation.Builder(rh.gs(config.appName))
                .setLatestTimestamp(System.currentTimeMillis())
                .setReadPendingIntent(msgReadPendingIntent)
                .setReplyAction(msgReplyPendingIntent, remoteInput)
            unreadConversationBuilder.addMessage(aaMsg)
            /// End Android Auto
        } else {
            line1 = rh.gs(app.aaps.core.ui.R.string.no_profile_set)
        }
        val content = "$line1|$line2|$line3"
        val nowMono = android.os.SystemClock.elapsedRealtime()
        val publishAuto = includeAuto && content != lastAutoNotificationContent &&
            (lastAutoNotificationAt == Long.MIN_VALUE || nowMono - lastAutoNotificationAt >= 10_000L)
        if (publishAuto) { lastAutoNotificationContent = content; lastAutoNotificationAt = nowMono }
        val builder = NotificationCompat.Builder(context, notificationHolder.channelID)
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
        builder.setSmallIcon(iconsProvider.getNotificationIcon())
        builder.setContentTitle(line1)
        if (line2 != null) builder.setContentText(line2)
        if (line3 != null) builder.setSubText(line3)
        /// Android Auto
        if (publishAuto && unreadConversationBuilder != null) {
            builder.extend(
                NotificationCompat.CarExtender()
                    .setLargeIcon(rh.decodeResource(iconsProvider.getIcon()))
                    .setUnreadConversation(unreadConversationBuilder.build())
            )
        }
        /// End Android Auto
        builder.setContentIntent(notificationHolder.openAppIntent(context))
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = builder.build()
        mNotificationManager.notify(notificationHolder.notificationID, notification)
        notificationHolder.notification = notification
        buildGate.commit(refreshKey)
        lastBuildIncludedAuto = includeAuto
    }
}
