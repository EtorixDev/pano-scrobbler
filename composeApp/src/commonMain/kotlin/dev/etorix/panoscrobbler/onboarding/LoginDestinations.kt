package dev.etorix.panoscrobbler.onboarding

import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.UserAccountTemp
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import io.ktor.http.URLBuilder
import io.ktor.http.set

object LoginDestinations {
    fun route(accountType: AccountType): PanoRoute = when (accountType) {
        AccountType.LASTFM -> {
            val userAccountTemp = UserAccountTemp(
                AccountType.LASTFM,
                "",
            )

            if (!PlatformStuff.isTv) {
                val urlBuilder = URLBuilder("https://www.last.fm/api/auth")
                urlBuilder.set {
                    parameters.append("api_key", Requesters.lastfmUnauthedRequester.apiKey)
                    parameters.append("cb", "${Stuff.DEEPLINK_SCHEME}://auth/lastfm")
                }

                PanoRoute.WebView(
                    url = urlBuilder.buildString(),
                    userAccountTemp = userAccountTemp
                )
            } else {
                PanoRoute.OobLastfmLibreFmAuth(
                    userAccountTemp = userAccountTemp,
                )
            }
        }

        AccountType.LIBREFM -> {
            val userAccountTemp = UserAccountTemp(
                AccountType.LIBREFM,
                "",
                Stuff.LIBREFM_API_ROOT
            )

            PanoRoute.OobLastfmLibreFmAuth(
                userAccountTemp = userAccountTemp,
            )
        }

        AccountType.GNUFM -> PanoRoute.LoginGnufm
        AccountType.LISTENBRAINZ -> PanoRoute.LoginListenBrainz
        AccountType.CUSTOM_LISTENBRAINZ -> PanoRoute.LoginCustomListenBrainz
        AccountType.PLEROMA -> PanoRoute.LoginPleroma
        AccountType.FILE -> PanoRoute.LoginFile
    }
}