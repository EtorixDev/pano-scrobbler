package dev.etorix.panoscrobbler.themes

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.RippleThemeConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.etorix.panoscrobbler.pref.MainPrefs
import dev.etorix.panoscrobbler.ui.getActivityOrNull
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue

data class ThemePreviewSettings(
    val themeHue: Float,
    val themeStyle: PaletteStyle,
    val dynamic: Boolean,
    val random: Boolean,
    val dayNightMode: DayNightMode,
    val contrastMode: ContrastMode,
    val alpha: Float,
    val blurMainWindow: Boolean,
    val blurSubWindow: Boolean,
)

object ThemePreviewController {
    var previewSettings: ThemePreviewSettings? by mutableStateOf(null)
        private set

    fun startPreview(settings: ThemePreviewSettings) {
        if (previewSettings == null)
            previewSettings = settings
    }

    fun updatePreview(settings: ThemePreviewSettings) {
        previewSettings = settings
    }

    fun clearPreview() {
        previewSettings = null
    }
}

@Composable
fun AppTheme(
    onInitDone: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val prefsVersion by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.version }
    val themeHue by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.themeHueP
    }
    val themeStyle by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { p ->
        PaletteStyle.entries.find { it.name == p.themeStyle } ?: ThemeUtils.defaultThemeStyle
    }
    val dynamic by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeDynamic }
    val random by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeRandom }
    val randomHue by remember { ThemeUtils.randomHueForProcess }
    val dayNightMode by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeDayNight }
    val contrastMode by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeContrast }
    val blurMainWindowPref by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.themeBlurMainWindow
    }
    var osWindowBlur by rememberSaveable { mutableStateOf(false) }
    val blurSubWindowPref by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.themeBlurSubWindow
    }

    val alpha by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        if (PlatformStuff.isTv)
            1f
        else
            it.themeAlpha.coerceIn(MainPrefs.PREF_MIN_ALPHA, 1f)
    }

    val isSystemInDarkTheme by isSystemInDarkThemeNative()
    val activity = getActivityOrNull()
    val previewSettings = ThemePreviewController.previewSettings
    val activeThemeHue = previewSettings?.themeHue ?: themeHue
    val activeThemeStyle = previewSettings?.themeStyle ?: themeStyle
    val activeDynamic = previewSettings?.dynamic ?: dynamic
    val activeRandom = previewSettings?.random ?: random
    val activeDayNightMode = previewSettings?.dayNightMode ?: dayNightMode
    val activeContrastMode = previewSettings?.contrastMode ?: contrastMode
    val activeAlpha = previewSettings?.alpha ?: alpha
    val activeBlurMainWindow = previewSettings?.blurMainWindow ?: blurMainWindowPref
    val activeBlurSubWindow = previewSettings?.blurSubWindow ?: blurSubWindowPref
    val blurMainWindow = PlatformStuff.supportsBlur && activeBlurMainWindow && osWindowBlur
    val blurSubWindow = PlatformStuff.supportsBlur && activeBlurSubWindow && osWindowBlur

    if (prefsVersion == 0)
        return


    LaunchedEffect(Unit) {
        setupWindowBlurListener(activity) { osWindowBlur = it }
        onInitDone()
    }

    val isDark = activeDayNightMode == DayNightMode.DARK ||
            (activeDayNightMode == DayNightMode.SYSTEM && isSystemInDarkTheme)

    val themeAttributes = remember(
        isDark,
        activeContrastMode,
        activeThemeHue,
        activeThemeStyle,
        activeAlpha,
        blurMainWindow,
        blurSubWindow
    ) {
        val avatarColors = ThemeUtils.themeHues
            .filter { it != activeThemeHue }
            .map {
                ThemeUtils.avatarColors(
                    seedColor = ThemeUtils.getThemeColor(it),
                    style = activeThemeStyle,
                    isDark = isDark,
                    contrastMode = activeContrastMode
                )
            }

        ThemeAttributes(
            isDark = isDark,
            isTranslucent = activeAlpha < 1f,
            blurMainWindow = blurMainWindow,
            blurSubWindow = blurSubWindow,
            contrastMode = activeContrastMode,
            style = activeThemeStyle,
            avatarContainerColors = avatarColors.map { it.first },
            avatarColors = avatarColors.map { it.second },
        )
    }

    val colorScheme: ColorScheme = when {
        activeDynamic && PlatformStuff.supportsDynamicColors -> {
            remember(isDark, activeAlpha, blurSubWindow) {
                getDynamicColorScheme(activity, isDark)
                    .withAlpha(activeAlpha, blurSubWindow)
            }
        }

        else -> {
            remember(
                activeContrastMode,
                activeThemeHue,
                activeThemeStyle,
                isDark,
                activeRandom,
                randomHue,
                activeAlpha,
                blurSubWindow
            ) {
                val hue = if (activeRandom)
                    randomHue
                else
                    activeThemeHue

                ThemeUtils.materialColorScheme(
                    seedColor = ThemeUtils.getThemeColor(hue),
                    isDark = isDark,
                    style = activeThemeStyle,
                    contrastMode = activeContrastMode,
                ).withAlpha(activeAlpha, blurSubWindow)
            }
        }
    }


    MaterialExpressiveTheme(
        colorScheme = colorScheme,
    ) {
        val rippleConfig = remember {
            RippleThemeConfiguration(
                focus = RippleThemeConfiguration.Focus.InsetRing(
                    outerStrokeInset = 0.dp,
                    outerStrokeWidth = 3.dp, // default is 2.dp — bumped for visibility on TV
                    innerStrokeInset = 1.dp,
                    innerStrokeWidth = 4.dp, // default is 3.dp
                )
            )
        }

        CompositionLocalProvider(
            LocalRippleThemeConfiguration provides rippleConfig,
            LocalThemeAttributes provides themeAttributes,
        ) {
            AddAdditionalProviders {
                content()
            }
        }
    }
}

private fun ColorScheme.withAlpha(alpha: Float, hasBlur: Boolean): ColorScheme {
    fun boostAlpha(alpha: Float, boost: Float) = alpha + (1f - alpha) * boost

    if (alpha == 1f && !hasBlur) return this

    val midAlpha = boostAlpha(alpha.coerceIn(0.5f, 1f), 0.6f)
    val highAlpha = boostAlpha(alpha.coerceIn(0.5f, 1f), 0.8f)

    return copy(
        background = background.copy(alpha = alpha),
        surface = surface.copy(alpha = alpha),
        surfaceContainerLow = surfaceContainerLow.copy(alpha = if (hasBlur) midAlpha else highAlpha),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = if (hasBlur) midAlpha else highAlpha),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = if (hasBlur) midAlpha else highAlpha),
        surfaceContainer = surfaceContainer.copy(alpha = if (hasBlur) midAlpha else highAlpha),
        outlineVariant = if (alpha < 1f)
            lerp(outlineVariant, outline, alpha.coerceAtLeast(0.65f)) // fix for bad contrast
        else
            outlineVariant,
//        secondaryContainer = secondaryContainer.copy(alpha = highAlpha),
//        tertiaryContainer = tertiaryContainer.copy(alpha = highAlpha),
//        inverseSurface = inverseSurface.copy(alpha = highAlpha),
    )
}

@Composable
fun AppPreviewTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme {
        content()
    }
}

@Composable
expect fun isSystemInDarkThemeNative(): State<Boolean>

expect fun getDynamicColorScheme(context: Any?, dark: Boolean): ColorScheme

@Composable
expect fun AddAdditionalProviders(content: @Composable () -> Unit)

expect fun setupWindowBlurListener(activity: Any?, onBlurChanged: (Boolean) -> Unit)
