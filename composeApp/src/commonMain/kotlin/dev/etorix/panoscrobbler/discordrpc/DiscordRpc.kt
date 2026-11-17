package dev.etorix.panoscrobbler.discordrpc

import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.BuildKonfig
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.ScrobbleEverywhere
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.media.PlayingTrackInfo
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.media.notifyPlayingTrackEvent
import dev.etorix.panoscrobbler.pref.AppItem
import dev.etorix.panoscrobbler.pref.MainPrefs
import dev.etorix.panoscrobbler.ui.accountTypeStringRes
import dev.etorix.panoscrobbler.utils.PanoNotifications
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.profile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface DiscordActivity {
    // https://discord.com/developers/docs/social-sdk/classdiscordpp_1_1ActivityAssets.html
    data class Activity(
        val discordClientId: String,
        val hash: Int,
        val scrobbleData: ScrobbleData,
        val name: String,
        // If specified, must be a string between 2 and 128 characters.
        val state: String,
        // If specified, must be a string between 2 and 128 characters.
        val details: String,
        // If specified, must be a string between 2 and 128 characters.
        val largeText: String,
        val startTimeMillis: Long,
        val durationMillis: Long?,
        // If specified, must be a string between 1 and 300 characters.
        val artUrl: String,
        // If specified, must be a string between 2 and 256 characters.
        val detailsUrl: String,
        val statusLine: Int,
        val buttonText: String,
        val buttonUrl: String,
        val isPlaying: Boolean,
        val showPausedForSecs: Int,
        val canFetchArt: Boolean,
    ) : DiscordActivity {

        /** Returns the start and end times. The end time is 0 if it is unknown. */
        fun timestampsMillis(now: Long): Pair<Long, Long> {
            val startTimeMillis = if (isPlaying || durationMillis == null)
                startTimeMillis
            else
                now - durationMillis

            val startTime = startTimeMillis

            val endTime = if (durationMillis != null && durationMillis > 0) {
                if (isPlaying)
                    startTime + (durationMillis)
                else
                    now
            } else {
                0
            }

            return startTime to endTime
        }
    }

    data object Clear : DiscordActivity
    data object Stop : DiscordActivity
}

private enum class DiscordRpcStatus {
    ACTIVITY_SET,
    ACTIVITY_CLEARED,
    ACTIVITY_STOPPED,
    FAILED,
}

object DiscordRpc {
    private val placeholderRegex by lazy {
        DiscordRpcPlaceholder.entries
            .joinToString(
                prefix = "\\$(",
                postfix = ")",
                separator = "|",
                transform = { it.name })
            .toRegex()
    }

    private val pausedHash = MutableStateFlow<Int?>(null)
    private val lastStatus = MutableStateFlow(DiscordRpcStatus.ACTIVITY_STOPPED)
    val wasSuccessful = lastStatus.map { it == DiscordRpcStatus.ACTIVITY_SET }
    private val retryDelay = 10.seconds
    private val clearIdleTimeout = 5.minutes
    private var collectorJob: Job? = null
    private val startMutex = Mutex()
    private val isRunning = MutableStateFlow(false)

    internal fun selectPlayingTrack(events: Map<String, PlayingTrackNotifyEvent.PlayingTrackState>) =
        events.values.filterIsInstance<PlayingTrackNotifyEvent.TrackPlaying>()
            .firstOrNull { it.preprocessed }

    fun stop() {
        isRunning.value = false
        pausedHash.value = null
    }

    fun start() {
        if (!startMutex.tryLock()) return
        try {
            isRunning.value = true
            if (collectorJob?.isActive == true) return

            collectorJob = PanoNotifications.playingTrackStates
                .mapLatest { events ->
                    delay(500.milliseconds)
                    selectPlayingTrack(events)
                }
                .combine(
                    PlatformStuff.mainPrefs.data.map { it.discordRpc }.distinctUntilChanged()
                        .combine(isRunning) { settings, running -> settings to running }
                ) { event, (settings, running) ->
                    when {
                        !running || !settings.enabled -> DiscordActivity.Stop
                        event == null -> DiscordActivity.Clear
                        else -> {
                            var buttonText = "via " + BuildKonfig.APP_NAME
                            var buttonUrl = Stuff.REPO_URL

                            suspend fun setProfileUrlAndText(accountType: AccountType) {
                                val profileUrl = Scrobblables.all
                                    .find { it.userAccount.type == accountType }
                                    ?.userAccount?.user?.url
                                if (profileUrl != null) {
                                    buttonUrl = profileUrl
                                    buttonText = getString(accountTypeStringRes(accountType).first) + " " +
                                            getString(Res.string.profile)
                                }
                            }

                            val buttonType =
                                MainPrefs.DiscordRpcSettings.ButtonType.entries.find { it.name == settings.buttonType }
                                    ?: MainPrefs.DiscordRpcSettings.ButtonType.PANO_SCROBBLER
                            when (buttonType) {
                                MainPrefs.DiscordRpcSettings.ButtonType.NONE -> {
                                    buttonText = ""
                                    buttonUrl = ""
                                }
                                MainPrefs.DiscordRpcSettings.ButtonType.LASTFM_PROFILE ->
                                    setProfileUrlAndText(AccountType.LASTFM)
                                MainPrefs.DiscordRpcSettings.ButtonType.LISTENBRAINZ_PROFILE ->
                                    setProfileUrlAndText(AccountType.LISTENBRAINZ)
                                MainPrefs.DiscordRpcSettings.ButtonType.LIBREFM_PROFILE ->
                                    setProfileUrlAndText(AccountType.LIBREFM)
                                MainPrefs.DiscordRpcSettings.ButtonType.PANO_SCROBBLER -> Unit
                            }

                            pausedHash.value = null
                            transform(
                                appName = PlatformStuff.loadApplicationLabel(event.scrobbleData.appId.orEmpty()),
                                trackPlaying = event,
                                buttonUrl = buttonUrl,
                                buttonText = buttonText,
                                settings = settings,
                            )
                        }
                    }
                }
                .scan(Pair<DiscordActivity?, DiscordActivity?>(null, null)) { previousPair, current ->
                    val previous = previousPair.first
                    Logger.d { "DiscordRpc: current=$current, previous=$previous" }
                    val effective = when {
                        current is DiscordActivity.Clear && previous == null -> null
                        current is DiscordActivity.Clear &&
                                previous is DiscordActivity.Activity &&
                                previous.hash == pausedHash.value &&
                                previous.showPausedForSecs > 0 ->
                            previous.copy(isPlaying = false)
                        else -> current
                    }
                    effective to previous
                }
                .map { it.first }
                .distinctUntilChanged()
                .transformLatest { activity ->
                    emit(activity)
                    if (activity is DiscordActivity.Clear) {
                        delay(clearIdleTimeout)
                        emit(DiscordActivity.Stop)
                    }
                }
                .mapLatest { activity ->
                    Logger.d { "DiscordRpc: activity=$activity" }
                    activity ?: return@mapLatest
                    var success = lastStatus.value != DiscordRpcStatus.FAILED
                    do {
                        if (!success) delay(retryDelay)
                        success = DiscordRpcPlatform.update(activity)
                        lastStatus.value = when {
                            success && activity is DiscordActivity.Activity -> DiscordRpcStatus.ACTIVITY_SET
                            success && activity is DiscordActivity.Clear -> DiscordRpcStatus.ACTIVITY_CLEARED
                            success && activity is DiscordActivity.Stop -> DiscordRpcStatus.ACTIVITY_STOPPED
                            else -> DiscordRpcStatus.FAILED
                        }

                        if (activity !is DiscordActivity.Activity) break

                        if (success && activity.artUrl.isEmpty() && activity.isPlaying && activity.canFetchArt)
                            fetchArt(activity.scrobbleData, activity.hash)

                        if (!success) {
                            Logger.d { "DiscordRpc: retrying" }
                        } else if (!activity.isPlaying && activity.showPausedForSecs > 0) {
                            withTimeoutOrNull(activity.showPausedForSecs.seconds) {
                                pausedHash.first { it != activity.hash }
                            }
                            success = DiscordRpcPlatform.update(DiscordActivity.Clear)
                            lastStatus.value = if (success)
                                DiscordRpcStatus.ACTIVITY_CLEARED
                            else
                                DiscordRpcStatus.FAILED

                            if (success) {
                                delay(clearIdleTimeout)
                                if (DiscordRpcPlatform.update(DiscordActivity.Stop))
                                    lastStatus.value = DiscordRpcStatus.ACTIVITY_STOPPED
                            }
                        }
                    } while (!success)
                }
                .launchIn(Stuff.appScope)
        } finally {
            startMutex.unlock()
        }
    }

    private suspend fun fetchArt(scrobbleData: ScrobbleData, hash: Int) {
        val additionalMetadata = ScrobbleEverywhere.fetchNowPlayingAlbumArt(scrobbleData)
        if (additionalMetadata.shouldFetchAgain) return

        notifyPlayingTrackEvent(
            PlayingTrackNotifyEvent.ArtUrlFetched(hash, additionalMetadata.artUrl.orEmpty())
        )
        if (!additionalMetadata.artUrl.isNullOrEmpty()) {
            Logger.d { "DiscordRpc artUrl fetched: ${additionalMetadata.artUrl}" }
        } else {
            Logger.e { "DiscordRpc artUrl: ${additionalMetadata.artUrl}" }
        }
    }

    private fun transform(
        appName: String,
        trackPlaying: PlayingTrackNotifyEvent.TrackPlaying,
        buttonUrl: String,
        buttonText: String,
        settings: MainPrefs.DiscordRpcSettings,
    ): DiscordActivity.Activity {
        val hash = trackPlaying.hash
        val state = formatLine(settings.line2Format, trackPlaying, appName, settings.lovedState)
        val details = formatLine(settings.line1Format, trackPlaying, appName, settings.lovedState)
        val largeText = formatLine(settings.line3Format, trackPlaying, appName, settings.lovedState)
        val name = formatLine(settings.nameFormat, trackPlaying, appName, settings.lovedState)
        val startTimeMillis =
            trackPlaying.timelineStartTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val durationMillis = trackPlaying.scrobbleData.duration
        val artUrl = trackPlaying.artUrlState.takeIf { settings.albumArt }?.url.orEmpty()
        val statusLine = settings.statusLine
        val detailsUrl = if (settings.detailsUrl)
            "https://www.last.fm/music/${trackPlaying.scrobbleData.artist.encodeURLPathPart()}/_/${trackPlaying.scrobbleData.track.encodeURLPathPart()}"
        else
            ""

        return DiscordActivity.Activity(
            discordClientId = Stuff.DISCORD_CLIENT_ID,
            hash = hash,
            scrobbleData = trackPlaying.scrobbleData,
            name = name.clamp(2, 128),
            state = state.clamp(2, 128),
            details = details.clamp(2, 128),
            largeText = largeText.clamp(2, 128),
            startTimeMillis = startTimeMillis,
            durationMillis = durationMillis,
            artUrl = artUrl.takeIf { it.length in 1..300 }.orEmpty(),
            detailsUrl = detailsUrl.takeIf { it.length in 2..256 }.orEmpty(),
            statusLine = statusLine,
            buttonText = buttonText,
            buttonUrl = buttonUrl,
            isPlaying = true,
            showPausedForSecs = settings.showPausedForSecs,
            canFetchArt = settings.albumArt &&
                    settings.albumArtFromNowPlaying &&
                    trackPlaying.artUrlState == PlayingTrackInfo.ArtUrlState.CanFetch,
        )
    }

    fun paused(hash: Int) {
        pausedHash.value = hash
    }

    fun clearPaused(hash: Int) {
        if (pausedHash.value == hash) pausedHash.value = null
    }

    private fun String.clamp(min: Int, max: Int): String {
        require(min <= max) { "min ($min) must be <= max ($max)" }
        return when {
            length < min -> padEnd(min)
            length > max -> take(max)
            else -> this
        }
    }

    private fun formatLine(
        template: String,
        trackPlaying: PlayingTrackNotifyEvent.TrackPlaying,
        appName: String,
        showLoved: Boolean,
    ): String {
        return template.replace(placeholderRegex) { match ->
            when (match.groupValues.getOrNull(1)) {
                DiscordRpcPlaceholder.artist.name -> trackPlaying.scrobbleData.artist
                DiscordRpcPlaceholder.title.name -> (if (trackPlaying.userLoved && showLoved) "❤ " else "") +
                        trackPlaying.scrobbleData.track

                DiscordRpcPlaceholder.albumArtist.name -> trackPlaying.scrobbleData.albumArtist
                    .orEmpty().ifEmpty { trackPlaying.scrobbleData.artist }

                DiscordRpcPlaceholder.album.name -> trackPlaying.scrobbleData.album.orEmpty()
                DiscordRpcPlaceholder.mediaPlayer.name -> {
                    trackPlaying.scrobbleData.appId?.let {
                        AppItem(it, appName).friendlyLabel
                    }.orEmpty()
                }

                else -> match.value
            }
        }
    }
}
