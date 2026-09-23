package dev.etorix.panoscrobbler

import dev.etorix.panoscrobbler.utils.DesktopStuff
import kotlinx.coroutines.flow.MutableSharedFlow

object DesktopWebView {

    var inited = false
        private set
    private var thread: Thread? = null
    private var callbackUrlAndCookies: MutableSharedFlow<Pair<String, Map<String, String>>>? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            if (inited)
                quit()

            // Native quit is asynchronous, so wait for its event loop to finish.
            thread?.takeIf { it.isAlive }?.join()
        })
    }

    @Suppress("UnsafeDynamicallyLoadedCode")
    fun load() {
        System.load(DesktopStuff.getLibraryPath("native_webview"))
    }

    fun init() {
        if (inited) return
        inited = true
        // Start the event loop in a separate thread
        thread = Thread {
            startEventLoop()
            inited = false
        }.apply {
            name = "WebviewEventLoopThread"
            start()
        }
    }

    fun setCallbackFlow(flow: MutableSharedFlow<Pair<String, Map<String, String>>>) {
        callbackUrlAndCookies = flow
    }

    fun closeP() {
        callbackUrlAndCookies = null
        close()
    }

    // jni callbacks

    @JvmStatic
    fun onCallback(url: String, cookies: Array<String>) {
        val cookiesMap = cookies.associate {
            val (name, value) = it.split("=", limit = 2)
            name to value
        }
        callbackUrlAndCookies?.tryEmit(url to cookiesMap)
    }

    @JvmStatic
    private external fun startEventLoop()

    @JvmStatic
    external fun launchWebView(
        url: String,
        callbackPrefix: String,
        cookiesUrl: String,
        dataDir: String,
        proxyType: Int,
        proxyHost: String,
        proxyPort: Int,
    )

    @JvmStatic
    private external fun close()

    @JvmStatic
    private external fun quit()
}
