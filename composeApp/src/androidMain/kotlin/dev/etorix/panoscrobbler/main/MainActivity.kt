package dev.etorix.panoscrobbler.main

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowInsetsControllerCompat
import dev.etorix.panoscrobbler.R
import dev.etorix.panoscrobbler.navigation.LocalActivityRestoredFlag
import dev.etorix.panoscrobbler.themes.AppTheme
import dev.etorix.panoscrobbler.themes.LocalThemeAttributes
import dev.etorix.panoscrobbler.utils.AndroidStuff.prolongSplashScreen
import dev.etorix.panoscrobbler.utils.applyAndroidLocaleLegacy

class MainActivity : ComponentActivity() {
    private val isTranslucentMarkerFile by lazy { noBackupFilesDir.resolve("is_translucent") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        var initDone = false
        prolongSplashScreen { initDone }

        setContent {
            AppTheme(
                onInitDone = { initDone = true }
            ) {
                val isDarkTheme = LocalThemeAttributes.current.isDark
                val isTranslucent = LocalThemeAttributes.current.isTranslucent

                LaunchedEffect(isDarkTheme) {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !isDarkTheme
                        isAppearanceLightNavigationBars = !isDarkTheme
                    }

                    if (Build.VERSION.SDK_INT in 26..27) {
                        // fix always light navigation bar on Oreo
                        val defaultLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
                        // The dark scrim color used in the platform.
                        // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/res/res/color/system_bar_background_semi_transparent.xml
                        // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/res/remote_color_resources_res/values/colors.xml;l=67
                        val defaultDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
                        window.navigationBarColor =
                            if (isDarkTheme)
                                defaultDarkScrim
                            else
                                defaultLightScrim
                    }
                }

                LaunchedEffect(isTranslucent) {
                    val markerFileExists = isTranslucentMarkerFile.exists()

                    if (isTranslucent && !markerFileExists) {
                        isTranslucentMarkerFile.createNewFile()
                        recreate()
                    } else if (!isTranslucent && markerFileExists) {
                        isTranslucentMarkerFile.delete()
                        recreate()
                    }
                }

                CompositionLocalProvider(LocalActivityRestoredFlag provides (savedInstanceState != null)) {
                    PanoAppContent()
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.applyAndroidLocaleLegacy() ?: return)
    }

    override fun onApplyThemeResource(theme: Resources.Theme, resid: Int, first: Boolean) {
        super.onApplyThemeResource(theme, resid, first)

        if (isTranslucentMarkerFile.exists())
            theme.applyStyle(R.style.Patch_Wallpaper, true)
    }
}