package dev.etorix.panoscrobbler.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.BuildKonfig
import dev.etorix.panoscrobbler.R
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.logger.JavaUtilFileLogger
import dev.etorix.panoscrobbler.utils.AndroidStuff
import dev.etorix.panoscrobbler.utils.AndroidStuff.toast
import dev.etorix.panoscrobbler.utils.MetadataUtils
import dev.etorix.panoscrobbler.utils.PanoNotifications
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.applyAndroidLocaleLegacy
import dev.etorix.panoscrobbler.utils.getStringInDeviceLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Instant

class NLService : NotificationListenerService() {
    private val mainPrefs = PlatformStuff.mainPrefs
    private var sessListener: SessListener? = null
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var scrobbleQueue: ScrobbleQueue
    private var job: Job? = null

    @Volatile
    private var inited = false

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.applyAndroidLocaleLegacy() ?: return)
    }

    // ListenBrainz app uses this, so it probably helps
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        PanoNotifications.fgNotiShown = false
    }

    override fun onListenerConnected() {
        //    This sometimes gets called twice without calling onListenerDisconnected or onDestroy
        //    onCreate seems to get called only once in those cases.
        //    also unreliable on lp and mm, which i am no longer supporting anyway
        // just gate them with an inited flag

        // in fact MediaSessionManager does not even need the service to be running. It just needs the permission

        if (!inited) {
            inited = true

            job = SupervisorJob()
            coroutineScope = CoroutineScope(Dispatchers.Main.immediate + job!!)

            if (BuildKonfig.DEBUG)
                toast(R.string.scrobbler_on)

            coroutineScope.launch {
                Stuff.initializeMainPrefsCache()

                val scrobblerEnabled = mainPrefs.data.map { it.scrobblerEnabled }.first()
                if (!scrobblerEnabled) {
                    requestUnbind()
                    inited = false
                }
            }.invokeOnCompletion {
                if (inited)
                    init()
            }
        }
    }

    private fun init() {
        coroutineScope.launch {
            PlatformStuff.mainPrefs.data.map { it.logToFileOnAndroid }.collect {
                Logger.config.logWriterList
                    .filterIsInstance<JavaUtilFileLogger>()
                    .firstOrNull()
                    ?.isEnabled = it
            }
        }

        coroutineScope.launch {
            PlatformStuff.mainPrefs.data.map { it.scrobblerEnabled }.collect {
                if (!it)
                    requestUnbind()
            }
        }

        if (PlatformStuff.isTv) {
            // TVs are low RAM devices, always use FGS
            PanoNotifications.startFgs(this)
        } else {
            coroutineScope.launch {
                PlatformStuff.mainPrefs.data.map { it.notiPersistent }
                    .collect { notiPersistent ->
                        if (notiPersistent) {
                            PanoNotifications.startFgs(this@NLService)
                        } else {
                            PanoNotifications.stopFgs(this@NLService)
                        }
                    }
            }
        }

        val sessManager = getSystemService(MediaSessionManager::class.java)!!
        scrobbleQueue = ScrobbleQueue(coroutineScope)

        sessListener = SessListener(coroutineScope, scrobbleQueue)

        try {
            sessManager.addOnActiveSessionsChangedListener(
                sessListener!!,
                ComponentName(this, this::class.java)
            )
            //scrobble after the app is updated
            sessListener?.onActiveSessionsChanged(
                sessManager.getActiveSessions(
                    ComponentName(
                        this,
                        this::class.java
                    )
                )
            )
        } catch (exception: SecurityException) {
            Logger.w(exception) { "Failed to start media controller" }
            // Try to unregister it, just in case.
            try {
                sessManager.removeOnActiveSessionsChangedListener(sessListener!!)
            } catch (e: Exception) {
                Logger.w(e) { "Failed to unregister media controller" }
            }
            // Media controller needs notification listener service permissions.
        }

//      Keep heavyweight app-level integrations out of this service to avoid unexplained ANRs

        if (BuildKonfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AndroidStuff.getScrobblerExitReasons().let {
                it.take(5).forEachIndexed { index, applicationExitInfo ->
                    Logger.w("${index + 1}. $applicationExitInfo", tag = "exitReasons")
                }
            }
        }

        coroutineScope.launch {
            listenForPlayingTrackEvents(scrobbleQueue, sessListener!!)
        }

        Logger.i { "init" }
    }

    private fun destroy() {
        inited = false

        Logger.i { "destroy" }

        if (sessListener != null) {
            sessListener?.removeSessions(emptySet())
            getSystemService(MediaSessionManager::class.java)!!
                .removeOnActiveSessionsChangedListener(sessListener!!)
            sessListener = null
            scrobbleQueue.shutdown()
        }
        job?.cancel()
    }

    override fun onDestroy() {
        if (AndroidStuff.canShowPersistentNotiIfEnabled)
            PanoNotifications.stopFgs(this)
        if (BuildKonfig.DEBUG)
            toast(R.string.scrobbler_off)

        destroy()

        super.onDestroy()
    }

    private suspend fun shouldScrobbleFromNoti(pkgName: String): Boolean {
        val preventDuplicateAmbientScrobbles =
            mainPrefs.data.map { it.preventDuplicateAmbientScrobbles }.first()

        return sessListener?.shouldScrobble(pkgName) == true &&
                !(preventDuplicateAmbientScrobbles && sessListener?.isMediaPlaying() == true)
    }

    // don't do file reads here
    private fun shouldCheckNoti(sbn: StatusBarNotification?): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                sbn != null &&
                (sbn.packageName == Stuff.PACKAGE_SHAZAM &&
                        (sbn.notification.channelId == Stuff.CHANNEL_SHAZAM || sbn.notification.channelId == Stuff.CHANNEL_SHAZAM2) &&
                        sbn.notification.actions != null ||
                        sbn.packageName in Stuff.PACKAGES_PIXEL_NP && sbn.notification.channelId == Stuff.CHANNEL_PIXEL_NP ||
                        sbn.packageName == Stuff.PACKAGE_AUDILE && sbn.notification.channelId in Stuff.CHANNELS_AUDILE &&
                        sbn.notification.extras.containsKey(Stuff.AUDILE_METADATA_KEY_TRACK_TITLE) &&
                        sbn.notification.extras.containsKey(Stuff.AUDILE_METADATA_KEY_TRACK_ARTIST)
                        )

    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!shouldCheckNoti(sbn))
            return

        coroutineScope.launch {
            if (!shouldScrobbleFromNoti(sbn!!.packageName))
                return@launch

            val n = sbn.notification
            if (sbn.packageName == Stuff.PACKAGE_SHAZAM)
                scrobbleFromNoti {
                    val title = n.extras.getString(Notification.EXTRA_TITLE)
                    val artist = n.extras.getString(Notification.EXTRA_TEXT)
                    if (title != null && artist != null) {
                        ScrobbleData(
                            artist = artist,
                            track = title,
                            album = null,
                            timestamp = System.currentTimeMillis(),
                            albumArtist = null,
                            duration = null,
                            appId = sbn.packageName
                        )
                    } else {
                        null
                    }
                }
            else if (sbn.packageName in Stuff.PACKAGES_PIXEL_NP)
                scrobbleFromNoti {
                    val artistTitlePair = MetadataUtils.scrobbleFromNotiExtractMeta(
                        n.extras.getString(Notification.EXTRA_TITLE) ?: "",
                        getStringInDeviceLocale(R.string.song_format_string)
                    )

                    if (artistTitlePair != null) {
                        val (artist, title) = artistTitlePair
                        ScrobbleData(
                            artist = artist,
                            track = title,
                            album = null,
                            timestamp = System.currentTimeMillis(),
                            albumArtist = null,
                            duration = null,
                            appId = sbn.packageName
                        )
                    } else {
                        null
                    }
                }
            else if (sbn.packageName == Stuff.PACKAGE_AUDILE)
                scrobbleFromNoti {
                    val title = n.extras.getString(Stuff.AUDILE_METADATA_KEY_TRACK_TITLE)
                    val artist = n.extras.getString(Stuff.AUDILE_METADATA_KEY_TRACK_ARTIST)
                    val album = n.extras.getString(Stuff.AUDILE_METADATA_KEY_TRACK_ALBUM)
                    val duration = n.extras.getLong(Stuff.AUDILE_METADATA_KEY_TRACK_DURATION, -1)
                        .takeIf { it > 0 }
                    val timestamp =
                        n.extras.getString(Stuff.AUDILE_METADATA_KEY_TRACK_SAMPLE_TIMESTAMP)
                            ?.let {
                                Instant.parseOrNull(it)?.toEpochMilliseconds()
                            } ?: System.currentTimeMillis()

                    if (!title.isNullOrEmpty() && !artist.isNullOrEmpty()) {
                        ScrobbleData(
                            artist = artist,
                            track = title,
                            album = album,
                            timestamp = timestamp,
                            albumArtist = null,
                            duration = duration,
                            appId = sbn.packageName
                        )
                    } else {
                        null
                    }
                }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) { //only for >26
        if (!shouldCheckNoti(sbn) ||
            !(reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL || reason == REASON_TIMEOUT || reason == REASON_ERROR)
        )
            return

        if (sbn?.packageName in Stuff.PACKAGES_PIXEL_NP)
            stopScrobbleFromNoti(sbn!!.packageName)
    }


    private fun stopScrobbleFromNoti(pkgName: String) {
        val trackInfo = sessListener?.createTrackInfo(
            appId = pkgName,
            notiKey = "$pkgName|$TAG_NOTI",
        ) ?: return

        scrobbleQueue.remove(trackInfo.lastScrobbleHash)

        PanoNotifications.removeNotificationByKey("$pkgName|$TAG_NOTI")
    }

    private fun scrobbleFromNoti(transformIntoScrobbleData: () -> ScrobbleData?) {
        val delay = 15 * 1000L
        val cooldown = 5 * 60 * 1000L
        val scrobbleData = transformIntoScrobbleData() ?: return
        val pkgName = scrobbleData.appId ?: return
        val needsDelayAndCooldown = pkgName in Stuff.PACKAGES_PIXEL_NP
        val trackInfo = sessListener?.createTrackInfo(
            appId = pkgName,
            notiKey = "$pkgName|$TAG_NOTI",
        ) ?: return

        trackInfo.putOriginals(
            artist = scrobbleData.artist,
            title = scrobbleData.track,
            album = scrobbleData.album.orEmpty(),
            albumArtist = scrobbleData.albumArtist.orEmpty(),
            durationMillis = scrobbleData.duration ?: 0L,
            normalizedUrlHost = null,
            artUrl = null,
            extraData = emptyMap()
        )

        val metadataChanged = !needsDelayAndCooldown ||
                (trackInfo.hash == trackInfo.lastScrobbleHash &&
                        System.currentTimeMillis() - trackInfo.playStartTime > cooldown) ||
                trackInfo.hash != trackInfo.lastScrobbleHash

        if (metadataChanged) {
            scrobbleQueue.remove(trackInfo.hash)

            scrobbleQueue.scrobble(
                trackInfo = trackInfo,
                appIsAllowListed = sessListener?.isAppAllowListed(pkgName) == true,
                delay = if (needsDelayAndCooldown) delay else 0L,
                timestampOverride = if (!needsDelayAndCooldown) scrobbleData.timestamp else null
            )
        }
    }

    companion object {
        private const val TAG_NOTI = "noti"
    }
}