package dev.etorix.panoscrobbler.fork

import dev.etorix.panoscrobbler.discordrpc.DiscordRpc
import dev.etorix.panoscrobbler.media.PlayingTrackInfo
import dev.etorix.panoscrobbler.utils.PanoNotifications
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecognitionPresenceTest {
    @AfterTest
    fun clearStates() {
        PanoNotifications.clearPlayingTrackStates()
    }

    private fun track(key: String, hasMediaSession: Boolean): PlayingTrackInfo {
        return PlayingTrackInfo(key, key, null, hasMediaSession).apply {
            putOriginals("Artist", "Track", "Album", "Artist", 1, 120_000, null, null, emptyMap())
            prepareForScrobbling()
            putPreprocessedData(toScrobbleData(false), false, true)
        }
    }

    @Test
    fun completedRecognitionLeavesOngoingMusicPresence() {
        val recognition = track("recognition", hasMediaSession = false)
        val music = track("music", hasMediaSession = true)
        PanoNotifications.updatePlayingTrackState(recognition.toTrackPlayingEvent())
        music.scrobbled()
        PanoNotifications.updatePlayingTrackState(music.toTrackPlayingEvent())
        assertTrue("recognition" in PanoNotifications.playingTrackStates.value)

        recognition.scrobbled()
        PanoNotifications.updatePlayingTrackState(recognition.toTrackPlayingEvent())
        assertFalse("recognition" in PanoNotifications.playingTrackStates.value)
        assertEquals("music", DiscordRpc.selectPlayingTrack(PanoNotifications.playingTrackStates.value)?.notiKey)

        PanoNotifications.clearPlayingTrackStates()
        assertTrue(PanoNotifications.playingTrackStates.value.isEmpty())
        assertNull(DiscordRpc.selectPlayingTrack(PanoNotifications.playingTrackStates.value))
    }

    @Test
    fun unfinishedMetadataDoesNotHideAPreprocessedSession() {
        val raw = track("raw-recognition", hasMediaSession = false)
            .toTrackPlayingEvent().copy(preprocessed = false)
        val music = track("music", hasMediaSession = true).toTrackPlayingEvent()
        val states = linkedMapOf(raw.notiKey to raw, music.notiKey to music)

        assertEquals(music, DiscordRpc.selectPlayingTrack(states))
    }
}
