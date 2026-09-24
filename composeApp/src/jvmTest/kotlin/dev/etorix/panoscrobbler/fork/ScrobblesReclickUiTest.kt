package dev.etorix.panoscrobbler.fork

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import com.sun.net.httpserver.HttpServer
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.UserAccountSerializable
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.main.MainViewModel
import dev.etorix.panoscrobbler.main.PanoAppContent
import dev.etorix.panoscrobbler.themes.AppTheme
import dev.etorix.panoscrobbler.utils.DesktopStuff
import dev.etorix.panoscrobbler.utils.PlatformStuff
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.loved
import pano_scrobbler.composeapp.generated.resources.recents
import kotlin.test.Test
import kotlin.system.exitProcess

class ScrobblesReclickUiTest {
    @Test
    fun selectedRecentsAndLovedTabsScrollBackToTop() {
        runIsolatedUiScenario("scrobbles-reclick", ScrobblesReclickUiScenario::class.java)
    }
}

/** Runs only in [ScrobblesReclickUiTest]'s isolated child process. */
object ScrobblesReclickUiScenario {
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

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val loved = exchange.requestURI.rawQuery.orEmpty()
                .contains("user.getLovedTracks", ignoreCase = true)
            val kind = if (loved) "Loved" else "Track"
            val responseKey = if (loved) "lovedtracks" else "recenttracks"
            val now = System.currentTimeMillis() / 1000
            val tracks = (0..99).reversed().joinToString(",") { index ->
                """{"name":"QA $kind ${index.toString().padStart(3, '0')}","artist":{"name":"QA Artist"},"album":{"name":"QA Album"},"date":{"uts":"${now - (100 - index) * 240}"}}"""
            }
            val response = """{"$responseKey":{"track":[$tracks],"@attr":{"page":"1","totalPages":"1","perPage":"100","total":"100"}}}"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val account = UserAccountSerializable(
                AccountType.GNUFM,
                UserCached("Isolated UI Test", "", "", "", 0),
                "local-test-token",
                "http://127.0.0.1:${server.address.port}/",
            )
            PlatformStuff.mainPrefs.updateData {
                it.copy(
                    scrobbleAccounts = listOf(account),
                    currentAccountType = AccountType.GNUFM,
                    desktopAppLearnt = true,
                    autoUpdates = false,
                )
            }
            withTimeout(5_000) {
                while (Scrobblables.current?.userAccount != account) delay(20)
            }

            val recentsTitle = getString(Res.string.recents)
            val lovedTitle = getString(Res.string.loved)

            runComposeUiTest {
                setContent { AppTheme { PanoAppContent(viewModel = remember { MainViewModel() }) } }

                waitUntil("Recent tracks loaded", 20_000) {
                    onAllNodesWithText("QA Track 099", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasScrollToIndexAction()).performScrollToIndex(50)
                onNodeWithText("QA Track 099", substring = true).assertDoesNotExist()

                // The selected top-bar button must deliver another SubTabClickedResult.
                waitUntil("Selected Recents button is visible", 10_000) {
                    onAllNodesWithText(recentsTitle).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(recentsTitle).performClick()
                waitUntil("Recents returned to the first track", 10_000) {
                    onAllNodesWithText("QA Track 099", substring = true).fetchSemanticsNodes().isNotEmpty()
                }

                waitUntil("Loved tab is available", 10_000) {
                    onAllNodesWithContentDescription(lovedTitle).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription(lovedTitle).performClick()
                waitUntil("Loved tracks loaded", 20_000) {
                    onAllNodesWithText("QA Loved 099", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasScrollToIndexAction()).performScrollToIndex(50)
                onNodeWithText("QA Loved 099", substring = true).assertDoesNotExist()

                waitUntil("Selected Loved button is visible", 10_000) {
                    onAllNodesWithText(lovedTitle).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(lovedTitle).performClick()
                waitUntil("Loved returned to the first track", 10_000) {
                    onAllNodesWithText("QA Loved 099", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
