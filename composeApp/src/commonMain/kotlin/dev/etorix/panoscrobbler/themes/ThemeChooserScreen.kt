package dev.etorix.panoscrobbler.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.etorix.panoscrobbler.icons.Check
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.themes.colors.ThemeVariants
import dev.etorix.panoscrobbler.ui.LabeledCheckbox
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.auto
import pano_scrobbler.composeapp.generated.resources.contrast
import pano_scrobbler.composeapp.generated.resources.dark
import pano_scrobbler.composeapp.generated.resources.high
import pano_scrobbler.composeapp.generated.resources.light
import pano_scrobbler.composeapp.generated.resources.low
import pano_scrobbler.composeapp.generated.resources.medium
import pano_scrobbler.composeapp.generated.resources.random_on_start
import pano_scrobbler.composeapp.generated.resources.system_colors

@Composable
fun ThemeChooserScreen(
    modifier: Modifier = Modifier,
) {
    val persistedThemeName by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeName }
    val persistedDynamic by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeDynamic }
    val persistedDayNightMode by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeDayNight }
    val persistedRandom by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeRandom }
    val persistedContrastMode by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.themeContrast }
    val isAppInNightMode = LocalThemeAttributes.current.isDark

    val persistedSettings = remember(
        persistedThemeName,
        persistedDynamic,
        persistedDayNightMode,
        persistedRandom,
        persistedContrastMode,
    ) {
        ThemePreviewSettings(
            themeName = persistedThemeName,
            dynamic = persistedDynamic,
            random = persistedRandom,
            dayNightMode = persistedDayNightMode,
            contrastMode = persistedContrastMode,
        )
    }

    LaunchedEffect(persistedSettings) {
        ThemePreviewController.startPreview(persistedSettings)
    }

    val previewSettings = ThemePreviewController.previewSettings ?: persistedSettings

    fun updatePreview(transform: ThemePreviewSettings.() -> ThemePreviewSettings) {
        ThemePreviewController.updatePreview(previewSettings.transform())
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeUtils.themesMap.forEach { (_, themeObj) ->
                ThemeSwatch(
                    themeVariants = themeObj,
                    isDark = isAppInNightMode,
                    selected = previewSettings.themeName == themeObj.name,
                    onClick = {
                        updatePreview { copy(themeName = themeObj.name) }
                    },
                    enabled = !previewSettings.dynamic && !previewSettings.random,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DayNightMode.entries.forEach {
                FilterChip(
                    label = { it.Label() },
                    selected = previewSettings.dayNightMode == it,
                    enabled = true,
                    onClick = {
                        updatePreview { copy(dayNightMode = it) }
                    }
                )
            }
        }

        Text(
            text = stringResource(Res.string.contrast),
            style = MaterialTheme.typography.bodyLarge,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (previewSettings.dynamic) 0.5f else 1f)
        ) {
            ContrastMode.entries.forEach {
                FilterChip(
                    label = { it.Label() },
                    enabled = !previewSettings.dynamic,
                    selected = previewSettings.contrastMode == it,
                    onClick = {
                        updatePreview { copy(contrastMode = it) }
                    }
                )
            }
        }

        if (PlatformStuff.supportsDynamicColors && !PlatformStuff.isTv) {
            LabeledCheckbox(
                text = stringResource(Res.string.system_colors),
                checked = previewSettings.dynamic,
                enabled = true,
                onCheckedChange = { checked ->
                    updatePreview { copy(dynamic = checked) }
                }
            )
        }

        LabeledCheckbox(
            text = stringResource(Res.string.random_on_start),
            checked = previewSettings.random,
            enabled = true,
            onCheckedChange = { checked ->
                updatePreview { copy(random = checked) }
            }
        )
    }
}

@Composable
private fun DayNightMode.Label() {
    when (this) {
        DayNightMode.LIGHT -> Text(stringResource(Res.string.light))
        DayNightMode.DARK -> Text(stringResource(Res.string.dark))
        DayNightMode.SYSTEM -> Text(stringResource(Res.string.auto))
    }
}

@Composable
private fun ContrastMode.Label() {
    when (this) {
        ContrastMode.LOW -> Text(stringResource(Res.string.low))
        ContrastMode.MEDIUM -> Text(stringResource(Res.string.medium))
        ContrastMode.HIGH -> Text(stringResource(Res.string.high))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeSwatch(
    themeVariants: ThemeVariants,
    isDark: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = remember(isDark) {
        if (isDark)
            themeVariants.dark.primary
        else
            themeVariants.light.primary
    }

    val secondaryColor = remember(isDark) {
        if (isDark)
            themeVariants.dark.secondary
        else
            themeVariants.light.secondary
    }

    val tertiaryColor = remember(isDark) {
        if (isDark)
            themeVariants.dark.tertiary
        else
            themeVariants.light.tertiary
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val toggleButtonShapes = ToggleButtonDefaults.shapes()

    FilledTonalIconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        interactionSource = interactionSource,
        shapes = IconToggleButtonShapes(
            toggleButtonShapes.shape,
            toggleButtonShapes.pressedShape,
            toggleButtonShapes.checkedShape
        ),
        enabled = enabled,
        modifier = modifier
            .size(72.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clip(
                    if (isFocused) toggleButtonShapes.pressedShape
                    else if (selected) toggleButtonShapes.checkedShape
                    else toggleButtonShapes.shape
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .align(Alignment.TopStart)
                    .background(primaryColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth(0.5f)
                    .align(Alignment.TopEnd)
                    .background(secondaryColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth(0.5f)
                    .align(Alignment.BottomEnd)
                    .background(tertiaryColor)
            )

            if (selected) {
                Icon(
                    imageVector = Icons.Check,
                    contentDescription = null,
//                    tint = if (isDark) Color.White else Color.Black,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun ThemeSwatchPreview() {
    ThemeSwatch(
        themeVariants = ThemeUtils.defaultTheme,
        selected = true,
        onClick = {},
        isDark = false,
        enabled = true,
        modifier = Modifier
    )
}