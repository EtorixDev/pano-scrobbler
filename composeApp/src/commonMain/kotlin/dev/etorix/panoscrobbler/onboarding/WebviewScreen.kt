package dev.etorix.panoscrobbler.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.etorix.panoscrobbler.api.UserAccountTemp
import dev.etorix.panoscrobbler.api.pleroma.PleromaOauthClientCreds
import dev.etorix.panoscrobbler.navigation.PanoRoute

@Composable
expect fun WebViewScreen(
    initialUrl: String,
    onSetTitle: (String) -> Unit,
    onBack: () -> Unit,
    onNavigate: (PanoRoute) -> Unit,
    modifier: Modifier = Modifier,
    userAccountTemp: UserAccountTemp? = null,
    pleromaOauthClientCreds: PleromaOauthClientCreds? = null,
    viewModel: WebViewVM = viewModel {
        WebViewVM(
            userAccountTemp,
            pleromaOauthClientCreds
        )
    },
)

fun handleWebViewStatus(
    webViewLoginState: WebViewLoginState,
    onNavigate: (PanoRoute) -> Unit,
    onSetStatusText: (String) -> Unit,
    onBack: () -> Unit,
) {
    when (webViewLoginState) {
        WebViewLoginState.None -> {
            onSetStatusText("")
        }

        WebViewLoginState.Unavailable -> {
            onNavigate(PanoRoute.Help("[FAQ-wv]"))
        }

        WebViewLoginState.Processing -> {
            onSetStatusText("⏳")
        }

        WebViewLoginState.Success -> {
            onBack()
        }

        is WebViewLoginState.Failed -> {
            onSetStatusText(webViewLoginState.errorMsg)
        }
    }
}