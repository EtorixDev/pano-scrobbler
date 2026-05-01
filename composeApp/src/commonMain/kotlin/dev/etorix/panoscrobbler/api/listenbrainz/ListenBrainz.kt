package dev.etorix.panoscrobbler.api.listenbrainz

import androidx.annotation.IntRange
import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.BuildKonfig
import dev.etorix.panoscrobbler.api.DrawerData
import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.Requesters.getPageResult
import dev.etorix.panoscrobbler.api.Requesters.getResult
import dev.etorix.panoscrobbler.api.Requesters.postResult
import dev.etorix.panoscrobbler.api.Requesters.setJsonBody
import dev.etorix.panoscrobbler.api.Scrobblable
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.ScrobbleResult
import dev.etorix.panoscrobbler.api.UserAccountSerializable
import dev.etorix.panoscrobbler.api.UserAccountTemp
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.cache.CacheStrategy
import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.ApiException
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.ImageSize
import dev.etorix.panoscrobbler.api.lastfm.LastFmImage
import dev.etorix.panoscrobbler.api.lastfm.MusicEntry
import dev.etorix.panoscrobbler.api.lastfm.PageAttr
import dev.etorix.panoscrobbler.api.lastfm.PageEntries
import dev.etorix.panoscrobbler.api.lastfm.PageResult
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.api.lastfm.Session
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.api.lastfm.User
import dev.etorix.panoscrobbler.charts.ListeningActivity
import dev.etorix.panoscrobbler.charts.TimePeriod
import dev.etorix.panoscrobbler.charts.TimePeriodType
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.db.PendingListenBrainzMutation
import dev.etorix.panoscrobbler.db.PendingListenBrainzMutationKind
import dev.etorix.panoscrobbler.db.SeenTrackAlbumAssociation
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.Stuff.cacheStrategy
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.util.appendIfNameAbsent
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min


class ListenBrainz(userAccount: UserAccountSerializable) : Scrobblable(userAccount) {

    private val client get() = Requesters.genericKtorClient
    private val outerScope = Stuff.appScope

    private fun HttpRequestBuilder.commonReq() {
        val existingPath = url.encodedPath

        url(userAccount.apiRoot + "1/" + existingPath)
        headers.appendIfNameAbsent(
            HttpHeaders.Authorization,
            "token ${userAccount.authKey}"
        )
    }

    private suspend fun submitListens(
        scrobbleDatas: List<ScrobbleData>,
        listenType: ListenBrainzListenType,
    ): Result<ScrobbleResult> {

//      "listened_at timestamp should be greater than 1033410600 (2002-10-01 00:00:00 UTC).",

        val listen = ListenBrainzListen(
            listenType,
            scrobbleDatas.map { scrobbleData ->

//                val pkgName = scrobbleData.pkgName?.let { PackageName(it) }

                ListenBrainzPayload(
                    scrobbleData.timestamp.takeIf { listenType != ListenBrainzListenType.playing_now },
                    ListenBrainzTrackMetadata(
                        artist_name = scrobbleData.artist,
                        release_name = scrobbleData.album?.ifEmpty { null },
                        track_name = scrobbleData.track,
                        additional_info = ListenBrainzAdditionalInfo(
                            duration_ms = scrobbleData.safeDuration(),
                            submission_client = BuildKonfig.APP_NAME,
                            submission_client_version = BuildKonfig.VER_NAME,
                        )
                    )
                )
            }
        )

        return client.postResult<ListenBrainzSubmitResponse>("submit-listens") {
            if (listenType == ListenBrainzListenType.playing_now)
                parameter("return_msid", true)

            setJsonBody(listen)
            commonReq()
        }.map {
            if (it.isOk)
                ScrobbleResult(false, msid = it.recording_msid)
            else
                ScrobbleResult(true)
        }
    }

    override suspend fun updateNowPlaying(scrobbleData: ScrobbleData) =
        submitListens(listOf(scrobbleData), ListenBrainzListenType.playing_now)

    override suspend fun scrobble(scrobbleData: ScrobbleData) =
        submitListens(listOf(scrobbleData), ListenBrainzListenType.single)

    override suspend fun scrobble(scrobbleDatas: List<ScrobbleData>) =
        submitListens(scrobbleDatas, ListenBrainzListenType.import)

    private suspend fun lookupMbid(track: Track): Result<ListenBrainzMbidLookup> {
        return client.getResult<ListenBrainzMbidLookup>("metadata/lookup") {
            parameter("artist_name", track.artist.name)
            parameter("recording_name", track.name)
            parameter("release_name", track.album?.name)
            commonReq()
        }
//        200 OK – lookup succeeded, does not indicate whether a match was found or not
    }

    private fun createImageMap(releaseMbid: String?): List<LastFmImage>? {
        return if (releaseMbid != null) listOf(
            LastFmImage(
                ImageSize.medium.name,
                "https://coverartarchive.org/release/$releaseMbid/front-250"
            ),
            LastFmImage(
                ImageSize.large.name,
                "https://coverartarchive.org/release/$releaseMbid/front-500"
            ),
            LastFmImage(
                ImageSize.extralarge.name,
                "https://coverartarchive.org/release/$releaseMbid/front-500"
            ),
        )
        else
            null
    }

    override suspend fun loveOrUnlove(track: Track, love: Boolean) =
        feedback(track, if (love) 1 else 0)

    suspend fun hate(track: Track) = feedback(track, -1)

    private suspend fun feedback(
        track: Track,
        @IntRange(-1, 1) score: Int,
    ): Result<ScrobbleResult> {
        val mbid = track.mbid
        var msid = track.msid?.takeIf { track.mbid == null }

        // send a temporary now playing for official listenbrainz servers
        if (mbid == null && msid == null && userAccount.apiRoot == Stuff.LISTENBRAINZ_API_ROOT) {
            val scrobbleData = ScrobbleData(
                artist = track.artist.name,
                track = track.name,
                album = track.album?.name,
                albumArtist = track.album?.artist?.name,
                timestamp = 0L,
                duration = track.duration,
                appId = null
            )
            updateNowPlaying(scrobbleData)
                .onSuccess {
                    msid = it.msid
                    Logger.d { "msid lookup result: $msid" }
                }.onFailure {
                    return Result.failure(it)
                }
        }


        if (msid == null && mbid == null) {
            Logger.w { "Track mbid/msid not found, skipping feedback" }
            return Result.success(ScrobbleResult(true)) // ignore
        }

        return client.postResult<ListenBrainzSubmitResponse>("feedback/recording-feedback") {
            setJsonBody(ListenBrainzFeedback(mbid, msid, score))
            commonReq()
        }.map {
            if (it.isOk)
                ScrobbleResult(false)
            else
                ScrobbleResult(true)
        }
    }


    override suspend fun getRecents(
        page: Int,
        username: String,
        cached: Boolean,
        from: Long,
        to: Long,
        includeNowPlaying: Boolean,
        limit: Int,
    ): Result<PageResult<Track>> {
        fun trackMap(it: ListenBrainzListensListens): Track {
            val artist = Artist(
                name = it.track_metadata.artist_name,
                mbid = it.track_metadata.mbid_mapping?.artist_mbids?.firstOrNull(),
            )

            val album = if (it.track_metadata.release_name != null)
                Album(
                    name = it.track_metadata.release_name,
                    mbid = it.track_metadata.mbid_mapping?.release_mbid,
                    artist = artist,
                    image = createImageMap(it.track_metadata.mbid_mapping?.release_mbid),
                )
            else
                null

            return Track(
                name = it.track_metadata.track_name,
                album = album,
                artist = artist,
                mbid = it.track_metadata.mbid_mapping?.recording_mbid,
                msid = it.recording_msid,
                duration = it.track_metadata.additional_info?.duration_ms,
                date = it.listened_at,
                userloved = null,
                isNowPlaying = it.playing_now == true,
            )
        }

        val cacheStrategy = if (cached)
            CacheStrategy.CACHE_ONLY_INCLUDE_EXPIRED
        else
            CacheStrategy.NETWORK_ONLY

        val actualLimit = if (limit > 0) limit else 25

        var listens =
            client.getPageResult<ListenBrainzListensData, Track>(
                "user/$username/listens",
                {
                    it.payload
                        .listens
                        .map { trackMap(it) }
                        .let { PageEntries(it) }
                },
                {
                    val oldestListenTs = it.payload.oldest_listen_ts
                    val oldestListenTsInPage = it.payload.listens.lastOrNull()?.listened_at
                    val totalPages =
                        if (oldestListenTsInPage != null && oldestListenTs != null && oldestListenTsInPage > oldestListenTs)
                            page + 1
                        else
                            page

                    PageAttr(
                        page,
                        totalPages,
                        it.payload.count,
                    )
                }
            ) {
                if (to > 0L)
                    parameter("max_ts", to / 1000)
                if (from > 0L)
                    parameter("min_ts", from / 1000)
                parameter("count", actualLimit)
                cacheStrategy(cacheStrategy)
                commonReq()
            }


        if (includeNowPlaying && !cached) {
            client.getResult<ListenBrainzListensData>("user/$username/playing-now") {
                cacheStrategy(CacheStrategy.NETWORK_ONLY)
                commonReq()
            }.map { it.payload.listens.firstOrNull()?.let { trackMap(it) } }
                .getOrNull()
                ?.let { npTrack ->
                    listens = listens.map {
                        it.copy(entries = listOf(npTrack) + it.entries)
                    }
                }
        }

        // run cache hook
        listens.onSuccess { pageResult ->
            outerScope.launch {
                val recentEntries = pageResult.entries.filter { !it.isNowPlaying }

                PanoDb.db.getSeenEntitiesDao().saveRecentTracks(
                    recentEntries,
                    mayHaveAlbumArt = false, // track charts have a release_mbid for album art unlike lastfm
                    savedLoved = false, // recents does not contain feedback info
                    priority = SeenTrackAlbumAssociation.Priority.RECENT_TRACKS,
                )

                if (!cached) {
                    processUnresolvedPendingEdits(recentEntries)
                    prunePendingMutations(username, recentEntries)
                }
            }
        }

        return listens
    }

    override suspend fun delete(track: Track): Result<Unit> {
        val listenedAtMillis = track.date ?: return Result.failure(IllegalStateException("no date"))
        val msid = track.msid ?: return Result.success(Unit) // ignore error

        val result = client.postResult<ListenBrainzSubmitResponse>("delete-listen") {
            setJsonBody(ListenBrainzDeleteRequest(listenedAtMillis, msid))
            commonReq()
        }

        if (result.isSuccess) {
            val dao = PanoDb.db.getPendingListenBrainzMutationsDao()
            PendingListenBrainzMutation.delete(userAccount, track)
                ?.let { dao.insertBounded(it) }
            dao.deleteUnresolved(
                apiRoot = PendingListenBrainzMutation.accountApiRoot(userAccount),
                username = userAccount.user.name,
                listenedAtMillis = listenedAtMillis,
            )
        }

        return result.map { }
    }

    suspend fun deleteLinkedListens(track: Track): Result<Unit> {
        val listenedAtMillis = track.date ?: return delete(track)
        val entries = getRecents(
            page = 1,
            cached = false,
            from = listenedAtMillis - 2_000L,
            to = listenedAtMillis + 2_000L,
            includeNowPlaying = false,
            limit = 25,
        ).getOrNull()?.entries

        val targets = entries
            ?.linkedDeleteTargets(track)
            .orEmpty()
            .ifEmpty { listOf(track) }

        var firstFailure: Throwable? = null
        targets.forEach { target ->
            delete(target)
                .onFailure {
                    if (firstFailure == null) {
                        firstFailure = it
                    }
                }
        }

        return firstFailure
            ?.let { Result.failure(it) }
            ?: Result.success(Unit)
    }

    private suspend fun List<Track>.linkedDeleteTargets(track: Track): List<Track> {
        val listenedAtMillis = track.date ?: return listOf(track)
        val activeMutations = PanoDb.db.getPendingListenBrainzMutationsDao()
            .activeForAccount(
                apiRoot = PendingListenBrainzMutation.accountApiRoot(userAccount),
                username = userAccount.user.name,
            )
            .filter { it.listenedAtMillis == listenedAtMillis }

        fun String?.metadataEquals(other: String?): Boolean {
            return orEmpty().trim().equals(other.orEmpty().trim(), ignoreCase = true)
        }

        fun Track.matchesTrackMetadata(other: Track): Boolean {
            val albumName = album?.name
            val otherAlbumName = other.album?.name
            val albumMatches = albumName.isNullOrBlank() ||
                    otherAlbumName.isNullOrBlank() ||
                    albumName.metadataEquals(otherAlbumName)

            return name.metadataEquals(other.name) &&
                    artist.name.metadataEquals(other.artist.name) &&
                    albumMatches
        }

        fun Track.matchesMutationMetadata(mutation: PendingListenBrainzMutation): Boolean {
            fun matchesMetadata(artist: String?, name: String?, albumName: String?): Boolean {
                val trackAlbumName = album?.name
                val albumMatches = albumName.isNullOrBlank() ||
                        trackAlbumName.isNullOrBlank() ||
                        albumName.metadataEquals(trackAlbumName)

                return name.metadataEquals(this.name) &&
                        artist.metadataEquals(this.artist.name) &&
                        albumMatches
            }

            return matchesMetadata(
                mutation.originalArtist,
                mutation.originalTrack,
                mutation.originalAlbum,
            ) || matchesMetadata(
                mutation.replacementArtist,
                mutation.replacementTrack,
                mutation.replacementAlbum,
            )
        }

        return (listOf(track) + this)
            .filter { !it.isNowPlaying && it.date != null && it.msid != null }
            .filter { candidate ->
                abs(candidate.date!! - listenedAtMillis) <= 2_000L &&
                        (candidate.date == listenedAtMillis ||
                                candidate.matchesTrackMetadata(track) ||
                                activeMutations.any { candidate.matchesMutationMetadata(it) })
            }
            .distinctBy { PendingListenBrainzMutation.identityKey(it) }
    }

    private suspend fun prunePendingMutations(
        username: String,
        entries: List<Track>,
    ) {
        val datedEntries = entries.filter { !it.isNowPlaying && it.date != null }
        if (datedEntries.isEmpty()) return

        val newest = datedEntries.maxOf { it.date!! }
        val oldest = datedEntries.minOf { it.date!! }
        val presentIdentities = datedEntries
            .mapNotNull { PendingListenBrainzMutation.identityKey(it) }
            .toSet()

        val dao = PanoDb.db.getPendingListenBrainzMutationsDao()
        val idsToDelete = dao.activeForAccount(
            apiRoot = PendingListenBrainzMutation.accountApiRoot(userAccount),
            username = username,
        )
            .filter { mutation ->
                mutation.kind != PendingListenBrainzMutationKind.UNRESOLVED_EDIT &&
                        mutation.listenedAtMillis in oldest..newest &&
                        mutation.identityKey !in presentIdentities
            }
            .map { it._id }

        if (idsToDelete.isNotEmpty())
            dao.delete(idsToDelete)
    }

    private suspend fun processUnresolvedPendingEdits(entries: List<Track>) {
        val dao = PanoDb.db.getPendingListenBrainzMutationsDao()
        val activeMutations = dao.activeForAccount(
            apiRoot = PendingListenBrainzMutation.accountApiRoot(userAccount),
            username = userAccount.user.name,
        )
        val activeMutationMap = activeMutations
            .filter { it.kind != PendingListenBrainzMutationKind.UNRESOLVED_EDIT }
            .groupBy { it.identityKey }
            .mapValues { (_, mutations) -> mutations.maxBy { it.createdAtMillis } }
        val unresolvedEdits = activeMutations
            .filter { it.kind == PendingListenBrainzMutationKind.UNRESOLVED_EDIT }

        if (unresolvedEdits.isEmpty()) return

        unresolvedEdits.forEach { mutation ->
            val originalTrack = entries
                .filter { !it.isNowPlaying && it.date != null && it.msid != null }
                .filter { mutation.matchesUnresolvedOriginal(it, maxDistanceMs = 5 * 60 * 1000L) }
                .minByOrNull { kotlin.math.abs(it.date!! - mutation.listenedAtMillis) }
                ?: return@forEach
            val originalIdentity = PendingListenBrainzMutation.identityKey(originalTrack)
            when (activeMutationMap[originalIdentity]?.kind) {
                PendingListenBrainzMutationKind.DELETE -> return@forEach
                PendingListenBrainzMutationKind.EDIT -> {
                    dao.deleteUnresolved(
                        apiRoot = mutation.apiRoot,
                        username = mutation.username,
                        listenedAtMillis = mutation.listenedAtMillis,
                    )
                    return@forEach
                }

                PendingListenBrainzMutationKind.UNRESOLVED_EDIT,
                null -> Unit
            }

            val replacementScrobbleData = ScrobbleData(
                artist = mutation.replacementArtist ?: originalTrack.artist.name,
                track = mutation.replacementTrack ?: originalTrack.name,
                album = mutation.replacementAlbum ?: originalTrack.album?.name,
                albumArtist = mutation.replacementAlbumArtist ?: originalTrack.album?.artist?.name,
                timestamp = mutation.listenedAtMillis,
                duration = mutation.replacementDuration ?: originalTrack.duration,
                appId = originalTrack.appId,
            )

            val scrobbleResult = scrobble(replacementScrobbleData)
            if (scrobbleResult.isFailure || scrobbleResult.getOrNull()?.ignored == true)
                return@forEach

            val deleteResult = delete(originalTrack)
            if (deleteResult.isFailure)
                return@forEach

            dao.deleteUnresolved(
                apiRoot = mutation.apiRoot,
                username = mutation.username,
                listenedAtMillis = mutation.listenedAtMillis,
            )
            PendingListenBrainzMutation.edit(
                userAccount = userAccount,
                originalTrack = originalTrack,
                replacementScrobbleData = replacementScrobbleData,
            )?.let { dao.insertBounded(it) }
        }
    }

    override suspend fun getLoves(
        page: Int,
        username: String,
        cacheStrategy: CacheStrategy,
        limit: Int,
    ): Result<PageResult<Track>> {
        fun mapTrack(it: ListenBrainzFeedbackPayload): Track {
            val artist = if (it.track_metadata?.artist_name != null)
                Artist(
                    name = it.track_metadata.artist_name,
                    mbid = it.track_metadata.mbid_mapping?.artist_mbids?.firstOrNull(),
                )
            else
                null

            val album = if (it.track_metadata?.release_name != null)
                Album(
                    name = it.track_metadata.release_name,
                    mbid = it.track_metadata.mbid_mapping?.release_mbid,
                    artist = artist!!,
                    image = createImageMap(it.track_metadata.mbid_mapping?.release_mbid),
                )
            else
                null

            return Track(
                name = it.track_metadata!!.track_name,
                album = album,
                artist = artist!!,
                mbid = it.recording_mbid,
                msid = it.recording_msid,
                duration = it.track_metadata.additional_info?.duration_ms,
                date = it.created,
                userloved = it.score == 1,
                userHated = it.score == -1,
            )
        }

        val actualLimit = if (limit > 0) limit else 25

        return client.getPageResult<ListenBrainzFeedbacks, Track>(
            "feedback/user/$username/get-feedback",
            {
                it.feedback
                    .filter { it.track_metadata != null }
                    .map { mapTrack(it) }
                    .let { PageEntries(it) }
            },
            {
                val totalPages = ceil(it.total_count.toFloat() / actualLimit).toInt()
                PageAttr(page, totalPages, it.total_count)
            }
        ) {
            parameter("offset", actualLimit * (page - 1))
            parameter("metadata", true)
            parameter("count", actualLimit)
            cacheStrategy(cacheStrategy)
            commonReq()
        }.onSuccess { feedbacks ->
            // run cache hook
            if (userAccount.user.isSelf) {
                outerScope.launch {
                    PanoDb.db.getSeenEntitiesDao().saveLovedTracks(feedbacks.entries)
                }
            }
        }
    }

    override suspend fun getFriends(
        page: Int,
        username: String,
        cached: Boolean,
        limit: Int,
    ): Result<PageResult<User>> {
        val cacheStrategy = if (cached) CacheStrategy.CACHE_ONLY_INCLUDE_EXPIRED
        else CacheStrategy.NETWORK_ONLY

        return client.getPageResult<ListenBrainzFollowing, User>(
            "user/$username/following",
            {
                it.following.map {
                    User(it, url = "https://listenbrainz.org/user/$it")
                }
                    .let { PageEntries(it) }
            }
        ) {
            cacheStrategy(cacheStrategy)
            commonReq()
        }
    }

    override suspend fun loadDrawerData(username: String): Result<DrawerData> {
        return client.getResult<ListenBrainzCountData>("user/$username/listen-count") {
            commonReq()
        }
            .map { DrawerData(it.payload.count) }
    }

    override suspend fun getCharts(
        type: Int,
        timePeriod: TimePeriod,
        page: Int,
        username: String,
        cacheStrategy: CacheStrategy,
        limit: Int,
    ): Result<PageResult<out MusicEntry>> {
        fun mapMusicEntry(it: ListenBrainzStatsEntry): MusicEntry {
            return when (type) {
                Stuff.TYPE_ARTISTS -> {
                    Artist(
                        name = it.artist_name,
                        mbid = it.artist_mbids?.firstOrNull(),
                        playcount = it.listen_count.toLong(),
                        userplaycount = it.listen_count,
                    )
                }

                Stuff.TYPE_ALBUMS -> {
                    Album(
                        name = it.release_name!!,
                        artist = Artist(
                            name = it.artist_name,
                            mbid = it.artist_mbids?.firstOrNull(),
                        ),
                        mbid = it.release_mbid,
                        playcount = it.listen_count.toLong(),
                        userplaycount = it.listen_count,
                        image = createImageMap(it.release_mbid),
                    )
                }

                Stuff.TYPE_TRACKS -> {
                    val artist = Artist(
                        name = it.artist_name,
                        mbid = it.artist_mbids?.firstOrNull(),
                    )

                    val album = it.release_name?.let { albumName ->
                        Album(
                            name = albumName,
                            artist = artist,
                            mbid = it.release_mbid,
                            image = createImageMap(it.release_mbid),
                        )
                    }

                    Track(
                        name = it.track_name!!,
                        album = album,
                        artist = artist,
                        mbid = it.recording_mbid,
                        playcount = it.listen_count.toLong(),
                        userplaycount = it.listen_count,
                    )
                }

                else -> {
                    throw IllegalArgumentException("Unknown type")
                }
            }
        }

        val range = timePeriod.listenBrainzRange ?: "all_time"
        val actualLimit = if (limit > 0) limit else 25

        val typeStr = when (type) {
            Stuff.TYPE_ARTISTS -> "artists"
            Stuff.TYPE_ALBUMS -> "releases"
            Stuff.TYPE_TRACKS -> "recordings"
            else -> throw IllegalArgumentException("Unknown type")
        }

        return client.getPageResult<ListenBrainzStatsEntriesData, MusicEntry>(
            "stats/user/$username/$typeStr",
            {
                it.payload.let {
                    when (type) {
                        Stuff.TYPE_ARTISTS -> it.artists
                        Stuff.TYPE_ALBUMS -> it.releases
                        Stuff.TYPE_TRACKS -> it.recordings
                        else -> throw IllegalArgumentException("Unknown type")
                    }
                }!!.map { mapMusicEntry(it) }
                    .let { PageEntries(it) }
            },
            {
                val total = it.payload.total_artist_count ?: it.payload.total_release_count
                ?: it.payload.total_recording_count ?: 0
                val totalPages = ceil(min(total, 1000).toFloat() / actualLimit).toInt()

                PageAttr(page, totalPages, total)
            }
        ) {
            parameter("offset", actualLimit * (page - 1))
            parameter("range", range)
            parameter("count", actualLimit)
            cacheStrategy(cacheStrategy)
            commonReq()
        }.onSuccess {
            // run cache hook
            if (type == Stuff.TYPE_TRACKS && userAccount.user.isSelf) { // they have albums sometimes, unlike lastfm
                outerScope.launch {
                    PanoDb.db.getSeenEntitiesDao().saveRecentTracks(
                        it.entries.filterIsInstance<Track>(),
                        mayHaveAlbumArt = false,
                        savedLoved = false,
                        priority = SeenTrackAlbumAssociation.Priority.CHARTS,
                    )
                }
            }
        }
    }

    override suspend fun getListeningActivity(
        timePeriod: TimePeriod,
        user: UserCached?,
        cacheStrategy: CacheStrategy,
    ): ListeningActivity {

        timePeriod.listenBrainzRange ?: return ListeningActivity()
        val username = user?.name ?: userAccount.user.name

        val result =
            client.getResult<ListenBrainzActivityData>("stats/user/$username/listening-activity") {
                parameter("range", timePeriod.listenBrainzRange)
                cacheStrategy(cacheStrategy)
                commonReq()
            }

        val type = when (timePeriod.listenBrainzRange) {
            ListenBrainzRange.all_time -> TimePeriodType.YEAR

            ListenBrainzRange.year,
            ListenBrainzRange.this_year,
            ListenBrainzRange.half_yearly
                -> TimePeriodType.MONTH

            else -> TimePeriodType.DAY
        }


        val dateFormatter = when (type) {
            TimePeriodType.YEAR -> SimpleDateFormat("''yy", Locale.getDefault())
            TimePeriodType.MONTH -> SimpleDateFormat("MM", Locale.getDefault())
            TimePeriodType.DAY -> SimpleDateFormat("dd.\nMM", Locale.getDefault())
            else -> null
        }

//        val n = when (type) {
//            TimePeriodType.YEAR -> 10
//            TimePeriodType.MONTH -> 12
//            else -> 15
//        }

        val timePeriodsMap = result.getOrNull()?.payload?.listening_activity
//            ?.takeLast(n)
            ?.associate {
                TimePeriod(
                    it.from_ts,
                    it.to_ts,
                    name = dateFormatter?.format(it.from_ts) ?: it.time_range
                ) to it.listen_count
            } ?: emptyMap()

        return ListeningActivity(
            timePeriodsToCounts = timePeriodsMap,
            type = type,
        )
    }

    companion object {

        suspend fun authAndGetSession(userAccountTemp: UserAccountTemp): Result<Session> {
            val client = Requesters.genericKtorClient

            val result =
                client.getResult<ValidateToken>("${userAccountTemp.apiRoot}1/validate-token") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "token ${userAccountTemp.authKey}")
                }

            result.onSuccess { validateToken ->
                validateToken.user_name ?: return Result.failure(ApiException(-1, "Invalid token"))
                val isCustom = userAccountTemp.apiRoot != Stuff.LISTENBRAINZ_API_ROOT
                val profileUrl = if (isCustom)
                    userAccountTemp.apiRoot!!.toHttpUrl()
                        .let { url -> url.scheme + "://" + url.host }
                else
                    "https://listenbrainz.org/user/${validateToken.user_name}"

                val account = UserAccountSerializable(
                    userAccountTemp.type,
                    UserCached(
                        validateToken.user_name,
                        profileUrl,
                        validateToken.user_name,
                        "",
                        -1,
                    ),
                    userAccountTemp.authKey,
                    userAccountTemp.apiRoot,
                )

                Scrobblables.add(account)
            }

            return result.map { Session(it.user_name, userAccountTemp.authKey) }
        }
    }
}
