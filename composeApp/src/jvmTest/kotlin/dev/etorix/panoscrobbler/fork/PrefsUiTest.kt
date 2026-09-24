package dev.etorix.panoscrobbler.fork

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.UserAccountSerializable
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.main.MainViewModel
import dev.etorix.panoscrobbler.main.PanoAppContent
import dev.etorix.panoscrobbler.themes.AppTheme
import dev.etorix.panoscrobbler.utils.DesktopStuff
import dev.etorix.panoscrobbler.utils.PanoTrayUtils
import dev.etorix.panoscrobbler.utils.PlatformStuff
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.back
import pano_scrobbler.composeapp.generated.resources.pref_sync_edits_across_services
import pano_scrobbler.composeapp.generated.resources.pref_use_track_progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.exitProcess

class PrefsUiTest {
    @Test
    fun desktopSettingsFocusAndRestoredSwitchesPersist() {
        runIsolatedUiScenario("prefs", PrefsUiScenario::class.java)
    }
}

/** Runs only in [PrefsUiTest]'s isolated child process. */
object PrefsUiScenario {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runBlocking { runScenario(Path.of(args.single())) }
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
        exitProcess(0)
    }

    @OptIn(ExperimentalTestApi::class)
    private suspend fun runScenario(data: Path) {
        Locale.setDefault(Locale.US)
        Files.createDirectories(data)
        DesktopStuff.parseCmdlineArgs(arrayOf("--data-dir", data.toString(), "--no-update-check"))
        val scrobbleFile = Files.createFile(data.resolve("scrobbles.csv"))
        val account = UserAccountSerializable(
            AccountType.FILE,
            UserCached("Isolated UI Test", "", "", "", 0),
            "csv",
            scrobbleFile.toUri().toString(),
        )
        PlatformStuff.mainPrefs.updateData {
            it.copy(
                scrobbleAccounts = listOf(account),
                currentAccountType = AccountType.FILE,
                useTrackProgress = false,
                syncEditsAcrossServices = true,
                desktopAppLearnt = true,
                autoUpdates = false,
            )
        }
        withTimeout(5_000) {
            while (Scrobblables.current?.userAccount != account) delay(20)
        }

        val progressTitle = getString(Res.string.pref_use_track_progress)
        val syncTitle = getString(Res.string.pref_sync_edits_across_services)
        val backTitle = getString(Res.string.back)

        runComposeUiTest {
            setContent { AppTheme { PanoAppContent(viewModel = remember { MainViewModel() }) } }
            waitForIdle()

            // This is the same desktop tray route that opens Settings in the running app.
            PanoTrayUtils.onTrayMenuItemClickedFn(PanoTrayUtils.ItemId.Settings.name)
            waitUntil("Settings search field is visible", 15_000) {
                onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            val search = onNode(hasSetTextAction())
            search.assertIsFocused()

            // Search limits the lazy settings list so these checks exercise the actual rows.
            search.performTextReplacement(progressTitle.take(4))
            waitUntil("Track progress row is visible", 10_000) {
                onAllNodesWithText(progressTitle).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(progressTitle).performClick()
            waitUntil("Track progress preference was saved", 10_000) {
                runBlocking { PlatformStuff.mainPrefs.data.first().useTrackProgressP }
            }

            search.performTextReplacement(syncTitle.take(4))
            waitUntil("Sync edits row is visible", 10_000) {
                onAllNodesWithText(syncTitle).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(syncTitle).performClick()
            waitUntil("Sync edits preference was saved", 10_000) {
                runBlocking { !PlatformStuff.mainPrefs.data.first().syncEditsAcrossServices }
            }

            onNodeWithContentDescription(backTitle).performClick()
            waitUntil("Settings search field has left composition", 10_000) {
                onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
            }
            PanoTrayUtils.onTrayMenuItemClickedFn(PanoTrayUtils.ItemId.Settings.name)
            waitUntil("Settings search field returned", 10_000) {
                onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            search.assertIsFocused()
        }

        // Read the actual DataStore once more after composition has closed.
        val saved = PlatformStuff.mainPrefs.data.first()
        assertTrue(saved.useTrackProgressP)
        assertEquals(false, saved.syncEditsAcrossServices)
    }
}
