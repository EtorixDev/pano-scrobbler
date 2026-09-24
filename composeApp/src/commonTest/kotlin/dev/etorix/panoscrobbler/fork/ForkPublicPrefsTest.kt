package dev.etorix.panoscrobbler.fork

import dev.etorix.panoscrobbler.pref.MainPrefs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkPublicPrefsTest {
    @Test
    fun publicExportAndImportPreserveNondefaultForkControls() {
        val source = MainPrefs(
            useTrackProgress = true,
            syncEditsAcrossServices = false,
            preventDuplicateAmbientScrobbles = true,
            linkHeartButtonToRating = true,
            discordRpc = MainPrefs.DiscordRpcSettings(
                enabled = true,
                detailsUrl = false,
                line1Format = "Now: \$artist",
            ),
        )

        val exported = Json.encodeToString(source.toPublicPrefs())
        val publicPrefs = Json.decodeFromString<MainPrefs.Public>(exported)
        val restored = MainPrefs().updateFromPublicPrefs(publicPrefs)

        assertTrue(restored.useTrackProgressP)
        assertFalse(restored.syncEditsAcrossServices)
        assertTrue(restored.preventDuplicateAmbientScrobbles)
        assertTrue(restored.linkHeartButtonToRating)
        assertEquals(source.discordRpc, restored.discordRpc)
    }

    @Test
    fun legacyPublicExportUsesDefaultsForMissingForkControls() {
        val legacy = Json.decodeFromString<MainPrefs.Public>("""{"scrobblerEnabled":false}""")
        val restored = MainPrefs().updateFromPublicPrefs(legacy)

        assertFalse(restored.scrobblerEnabled)
        assertFalse(restored.useTrackProgressP)
        assertTrue(restored.syncEditsAcrossServices)
        assertFalse(restored.preventDuplicateAmbientScrobbles)
        assertFalse(restored.linkHeartButtonToRating)
        assertFalse(restored.discordRpc.enabled)
    }
}
