package dev.etorix.panoscrobbler.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.core.DataStore
import androidx.room3.RoomDatabase
import dev.etorix.panoscrobbler.api.lastfm.MusicEntry
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.main.ScrobblerState
import dev.etorix.panoscrobbler.pref.MainPrefs
import java.io.File
import java.io.OutputStream
import java.net.Proxy

expect object PlatformStuff {

    val mainPrefs: DataStore<MainPrefs>

    val filesDir: File

    val cacheDir: File

    val logsDir: File

    fun getDeviceIdentifier(): String

    val isJava8OrGreater: Boolean

    val supportsDynamicColors: Boolean

    val isTv: Boolean

    val isDesktop: Boolean

    val hasSystemLocaleStore: Boolean

    val appIdPlaceholder: String

    suspend fun checkScrobblerState(requestRebind: Boolean): ScrobblerState

    fun openInBrowser(url: String)

    suspend fun launchSearchIntent(
        musicEntry: MusicEntry,
        appId: String?,
    )

    fun getDatabaseBuilder(): RoomDatabase.Builder<PanoDb>

    fun loadApplicationLabel(appId: String): String

    fun doesAppExist(appId: String): Boolean

    fun copyToClipboard(text: String)

    suspend fun writeBitmapToStream(imageBitmap: ImageBitmap, stream: OutputStream)

    fun getLocalIpAddresses(): List<String>

    fun monotonicTimeMs(): Long

    fun getSystemSocksProxy(): Proxy?
}