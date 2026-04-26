package dev.etorix.panoscrobbler.onboarding

import android.os.Bundle
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.etorix.panoscrobbler.api.UserAccountTemp
import dev.etorix.panoscrobbler.api.pleroma.PleromaOauthClientCreds
import dev.etorix.panoscrobbler.navigation.PanoRoute
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.loading

@Composable
actual fun WebViewScreen(
    initialUrl: String,
    onSetTitle: (String) -> Unit,
    onBack: () -> Unit,
    onNavigate: (PanoRoute) -> Unit,
    modifier: Modifier,
    userAccountTemp: UserAccountTemp?,
    pleromaOauthClientCreds: PleromaOauthClientCreds?,
    viewModel: WebViewVM,
) {
    val webViewClient = remember {
        PanoWebViewClient(
            disableNavigation = userAccountTemp == null && pleromaOauthClientCreds == null
        )
    }
    val loadingStr = stringResource(Res.string.loading)
    val webViewChromeClient = remember { PanoWebViewChromeClient() }
    val pageTitleState = remember { mutableStateOf(loadingStr) }
    val savedWebViewState = rememberSaveable { Bundle() }
    var statusText by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(pageTitleState.value) {
        onSetTitle(pageTitleState.value)
    }

    LaunchedEffect(Unit) {
        viewModel.loginState.collect { loginState ->
            handleWebViewStatus(
                loginState,
                onNavigate = onNavigate,
                onSetStatusText = { statusText = it },
                onBack = onBack,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let {
                savedWebViewState.clear()
                it.saveState(savedWebViewState)
                it.stopLoading()
                it.destroy()
            }
            webView = null
        }
    }

    if (statusText.isEmpty())
        AndroidView(
            factory = {
                WebView(it).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = false
                    settings.allowFileAccess = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        settings.isAlgorithmicDarkeningAllowed = true
                    }

                    webViewChromeClient.pageTitleState = pageTitleState
                    webViewClient.callbackUrlAndCookies = viewModel.callbackUrlAndCookies

                    this.webViewClient = webViewClient
                    this.webChromeClient = webViewChromeClient
                    webView = this

                    val restoredState =
                        if (savedWebViewState.keySet().isNotEmpty()) restoreState(savedWebViewState)
                        else null

                    if (restoredState == null) {
                        loadUrl(initialUrl)
                    }
                }
            },
            update = {
                webView = it
            },
            modifier = modifier
        )
    else
        Text(
            text = statusText,
            modifier = modifier
        )
}