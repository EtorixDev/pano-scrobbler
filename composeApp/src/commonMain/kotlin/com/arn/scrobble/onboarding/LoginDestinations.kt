package com.arn.scrobble.onboarding

import com.arn.scrobble.api.AccountType
import com.arn.scrobble.api.UserAccountTemp
import com.arn.scrobble.navigation.PanoRoute
import com.arn.scrobble.utils.Stuff

object LoginDestinations {
    fun route(accountType: AccountType): PanoRoute = when (accountType) {
        AccountType.LASTFM -> {
            val userAccountTemp = UserAccountTemp(
                AccountType.LASTFM,
                "",
            )

            PanoRoute.OobLastfmLibreFmAuth(
                userAccountTemp = userAccountTemp,
            )
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