package dev.etorix.panoscrobbler.onboarding

import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.DesktopWebView
import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.utils.DesktopStuff

actual fun WebViewVM.platformClear() {
    if (DesktopWebView.inited)
        DesktopWebView.deleteAndQuitP()
}

actual fun WebViewVM.platformInit() {
    val proxy = Requesters.proxy.value
    if (proxy.enabled && proxy.hasAuth && DesktopStuff.os != DesktopStuff.Os.Linux) {
        startProxyRelay(proxy)
    }

    try {
        DesktopWebView.load()
        DesktopWebView.init()
        DesktopWebView.setCallbackFlow(callbackUrlAndCookies)
    } catch (e: UnsatisfiedLinkError) {
        Logger.e(e) { "WebView unavailable" }
        loginState.value = WebViewLoginState.Unavailable
    }
}