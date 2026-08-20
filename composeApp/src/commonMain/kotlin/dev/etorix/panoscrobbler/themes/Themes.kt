package dev.etorix.panoscrobbler.themes

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.etorix.panoscrobbler.pref.MainPrefs
import dev.etorix.panoscrobbler.themes.colors.ThemeVariants
import dev.etorix.panoscrobbler.ui.getActivityOrNull
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import kotlin.math.abs
import kotlin.random.Random

private val randomNumberForProcess = abs(Random.nextInt())

data class ThemePreviewSettings(
    val themeName: String,
    val dynamic: Boolean,
    val random: Boolean,
    val dayNightMode: DayNightMode,
    val contrastMode: ContrastMode,
    val alpha: Float,
)

object ThemePreviewController {
    var previewSettings: ThemePreviewSettings? by mutableStateOf(null)
        private set

    fun startPreview(settings: ThemePreviewSettings) {
        if (previewSettings == null) {
            previewSettings = settings
        }
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
    val themeName by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeName }
    val dynamic by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeDynamic }
    val random by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeRandom }
    val dayNightMode by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeDayNight }
    val contrastMode by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeContrast }
    val blurMainWindowPref by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.themeBlurMainWindow
    }
    var osWindowBlur by rememberSaveable { mutableStateOf(false) }
    val blurSubWindowPref by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.themeBlurSubWindow
    }
    val blurMainWindow by remember(blurMainWindowPref, osWindowBlur) {
        mutableStateOf(PlatformStuff.supportsBlur && blurMainWindowPref && osWindowBlur)
    }

    val blurSubWindow by remember(blurSubWindowPref, osWindowBlur) {
        mutableStateOf(PlatformStuff.supportsBlur && blurSubWindowPref && osWindowBlur)
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

    val activeThemeName = previewSettings?.themeName ?: themeName
    val activeDynamic = previewSettings?.dynamic ?: dynamic
    val activeRandom = previewSettings?.random ?: random
    val activeDayNightMode = previewSettings?.dayNightMode ?: dayNightMode
    val activeContrastMode = previewSettings?.contrastMode ?: contrastMode
    val activeAlpha = previewSettings?.alpha ?: alpha

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
        activeThemeName,
        activeAlpha,
        blurMainWindow,
        blurSubWindow,
    ) {
        val otherColorSchemes = ThemeUtils.themesMap.values
            .filter { it.name != activeThemeName }
            .map {
                getColorScheme(
                    theme = it,
                    isDark = isDark,
                    contrastMode = activeContrastMode,
                )
            }

        ThemeAttributes(
            isDark = isDark,
            isTranslucent = activeAlpha < 1f,
            blurMainWindow = blurMainWindow,
            blurSubWindow = blurSubWindow,
            contrastMode = activeContrastMode,
            allOnSecondaryContainerColors = otherColorSchemes.map { it.onSecondaryContainer },
            allSecondaryContainerColors = otherColorSchemes.map { it.secondaryContainer },
        )
    }

    val colorScheme: ColorScheme = when {
        activeDynamic && PlatformStuff.supportsDynamicColors -> {
            getDynamicColorScheme(isDark).withAlpha(activeAlpha, blurSubWindow)
        }

        else -> {
            val theme = if (activeRandom)
                ThemeUtils.themesMap.values.toList()[randomNumberForProcess % ThemeUtils.themesMap.size]
            else
                ThemeUtils.themeNameToObject(activeThemeName)

            getColorScheme(
                theme = theme,
                isDark = isDark,
                contrastMode = activeContrastMode,
            ).withAlpha(activeAlpha, blurSubWindow)
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
    ) {
        CompositionLocalProvider(
            LocalThemeAttributes provides themeAttributes,
        ) {
            AddAdditionalProviders {
                content()
            }
        }
    }
}

private fun getColorScheme(
    theme: ThemeVariants,
    isDark: Boolean,
    contrastMode: ContrastMode,
): ColorScheme {
    return when {
        isDark && contrastMode == ContrastMode.LOW -> theme.dark
        isDark && contrastMode == ContrastMode.MEDIUM -> theme.darkMediumContrast
        isDark && contrastMode == ContrastMode.HIGH -> theme.darkHighContrast

        !isDark && contrastMode == ContrastMode.LOW -> theme.light
        !isDark && contrastMode == ContrastMode.MEDIUM -> theme.lightMediumContrast
        !isDark && contrastMode == ContrastMode.HIGH -> theme.lightHighContrast

        else -> theme.dark
    }
}

// Alpha is intentionally constrained by callers to the range 0.5f..1f.
private fun ColorScheme.withAlpha(alpha: Float, hasBlur: Boolean): ColorScheme {
    fun boostAlpha(value: Float, boost: Float) = value + (1f - value) * boost

    if (alpha == 1f && !hasBlur) return this

    val midAlpha = boostAlpha(alpha.coerceIn(0.5f, 1f), 0.6f)
    val highAlpha = boostAlpha(alpha.coerceIn(0.5f, 1f), 0.8f)
    return copy(
        background = background.copy(alpha = alpha),
        surface = surface.copy(alpha = alpha),
        surfaceContainerLow = surfaceContainerLow.copy(alpha = if (hasBlur) midAlpha else highAlpha),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = if (hasBlur) midAlpha else highAlpha),
        surfaceContainer = surfaceContainer.copy(alpha = highAlpha),
        secondaryContainer = secondaryContainer.copy(alpha = highAlpha),
        tertiaryContainer = tertiaryContainer.copy(alpha = highAlpha),
        inverseSurface = inverseSurface.copy(alpha = highAlpha),
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

@Composable
expect fun getDynamicColorScheme(dark: Boolean): ColorScheme

@Composable
expect fun AddAdditionalProviders(content: @Composable () -> Unit)

expect fun setupWindowBlurListener(activity: Any?, onBlurChanged: (Boolean) -> Unit)
