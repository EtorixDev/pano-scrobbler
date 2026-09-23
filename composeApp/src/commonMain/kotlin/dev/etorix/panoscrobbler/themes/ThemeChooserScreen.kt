package dev.etorix.panoscrobbler.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.etorix.panoscrobbler.BuildKonfig
import dev.etorix.panoscrobbler.icons.Casino
import dev.etorix.panoscrobbler.icons.CheckCircleFilled
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Palette
import dev.etorix.panoscrobbler.pref.MainPrefs
import dev.etorix.panoscrobbler.ui.LabeledCheckbox
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.alpha_notice
import pano_scrobbler.composeapp.generated.resources.appwidget_alpha
import pano_scrobbler.composeapp.generated.resources.auto
import pano_scrobbler.composeapp.generated.resources.blur
import pano_scrobbler.composeapp.generated.resources.blur_main_window
import pano_scrobbler.composeapp.generated.resources.blur_notice
import pano_scrobbler.composeapp.generated.resources.blur_sub_window
import pano_scrobbler.composeapp.generated.resources.contrast
import pano_scrobbler.composeapp.generated.resources.dark
import pano_scrobbler.composeapp.generated.resources.experimental
import pano_scrobbler.composeapp.generated.resources.high
import pano_scrobbler.composeapp.generated.resources.light
import pano_scrobbler.composeapp.generated.resources.low
import pano_scrobbler.composeapp.generated.resources.medium
import pano_scrobbler.composeapp.generated.resources.palette_cmf
import pano_scrobbler.composeapp.generated.resources.palette_expressive
import pano_scrobbler.composeapp.generated.resources.palette_tonal_spot
import pano_scrobbler.composeapp.generated.resources.palette_vibrant
import pano_scrobbler.composeapp.generated.resources.random_text
import pano_scrobbler.composeapp.generated.resources.system_colors

@Composable
fun ThemeChooserScreen(
    modifier: Modifier = Modifier,
) {
    val persistedPrefs by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it }
    val persistedSettings = remember(persistedPrefs) {
        ThemePreviewSettings(
            themeHue = persistedPrefs.themeHueP,
            themeStyle = PaletteStyle.entries.find { it.name == persistedPrefs.themeStyle }
                ?: ThemeUtils.defaultThemeStyle,
            dynamic = persistedPrefs.themeDynamic,
            random = persistedPrefs.themeRandom,
            dayNightMode = persistedPrefs.themeDayNight,
            contrastMode = persistedPrefs.themeContrast,
            alpha = persistedPrefs.themeAlpha,
            blurMainWindow = persistedPrefs.themeBlurMainWindow,
            blurSubWindow = persistedPrefs.themeBlurSubWindow,
        )
    }
    LaunchedEffect(persistedSettings) {
        ThemePreviewController.startPreview(persistedSettings)
    }
    val previewSettings = ThemePreviewController.previewSettings ?: persistedSettings
    val themeHue = previewSettings.themeHue
    val dynamic = previewSettings.dynamic
    val dayNightMode = previewSettings.dayNightMode
    val random = previewSettings.random
    val randomHue by remember { ThemeUtils.randomHueForProcess }
    val alpha = previewSettings.alpha
    val alphaIntPercent = (alpha * 100).toInt()
    val themeAttributes = LocalThemeAttributes.current
    val enableExperimental = false // todo testing only

    val previewColors =
        remember(themeAttributes.isDark, themeAttributes.style, themeAttributes.contrastMode) {
            ThemeUtils.themeHues.associateWith { hue ->
                ThemeUtils.themePreviewColors(
                    seedColor = ThemeUtils.getThemeColor(hue),
                    isDark = themeAttributes.isDark,
                    contrastMode = themeAttributes.contrastMode,
                    style = themeAttributes.style
                )
            }
        }

    fun save(block: MainPrefs.() -> MainPrefs) {
        val updated = persistedPrefs.copy(
            themeHue = previewSettings.themeHue,
            themeStyle = previewSettings.themeStyle.name,
            themeDynamic = previewSettings.dynamic,
            themeRandom = previewSettings.random,
            themeDayNight = previewSettings.dayNightMode,
            themeContrast = previewSettings.contrastMode,
            themeAlpha = previewSettings.alpha,
            themeBlurMainWindow = previewSettings.blurMainWindow,
            themeBlurSubWindow = previewSettings.blurSubWindow,
        ).block()
        ThemePreviewController.updatePreview(
            previewSettings.copy(
                themeHue = updated.themeHueP,
                themeStyle = PaletteStyle.entries.find { it.name == updated.themeStyle }
                    ?: ThemeUtils.defaultThemeStyle,
                dynamic = updated.themeDynamic,
                random = updated.themeRandom,
                dayNightMode = updated.themeDayNight,
                contrastMode = updated.themeContrast,
                alpha = updated.themeAlpha,
                blurMainWindow = updated.themeBlurMainWindow,
                blurSubWindow = updated.themeBlurSubWindow,
            )
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .alpha(if (dynamic) 0.5f else 1f)
        ) {
            Text(
                text = stringResource(Res.string.contrast),
                style = MaterialTheme.typography.bodyLarge,
            )

            ContrastMode.entries.forEach {
                FilterChip(
                    label = { it.Label() },
                    enabled = !dynamic,
                    selected = themeAttributes.contrastMode == it,
                    shapes = FilterChipDefaults.shapes(),
                    onClick = {
                        save {
                            copy(themeContrast = it)
                        }
                    }
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DayNightMode.entries.forEach {
                FilterChip(
                    label = { it.Label() },
                    selected = dayNightMode == it,
                    enabled = true,
                    shapes = FilterChipDefaults.shapes(),
                    onClick = {
                        save {
                            copy(themeDayNight = it)
                        }
                    }
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PaletteStyle.entries
                .filter { it != PaletteStyle.Cmf || enableExperimental }
                .forEach {
                    FilterChip(
                        label = { it.Label() },
                        selected = themeAttributes.style == it,
                        enabled = !dynamic,
                        shapes = FilterChipDefaults.shapes(),
                        onClick = {
                            save {
                                copy(themeStyle = it.name)
                            }
                        }
                    )
                }
        }

        HueSlider(
            hue = if (random) randomHue else themeHue,
            onHueChange = {
                save {
                    copy(themeHue = it, themeDynamic = false, themeRandom = false)
                }
            },
            enabled = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            ThemeUtils.themeHues.forEach { hue ->
                ThemeSwatch(
                    previewColors = previewColors.getValue(hue),
                    selected = themeHue == hue && !dynamic && !random,
                    onClick = {
                        save {
                            copy(
                                themeHue = hue,
                                themeDynamic = false,
                                themeRandom = false
                            )
                        }
                    },
                    enabled = true,
                )
            }

            if (PlatformStuff.supportsDynamicColors && !PlatformStuff.isTv) {
                ThemeSwatchLikeButton(
                    icon = Icons.Palette,
                    text = stringResource(Res.string.system_colors),
                    selected = dynamic,
                    onCheckedChange = {
                        if (it) {
                            save {
                                copy(
                                    themeDynamic = true,
                                    themeRandom = false
                                )
                            }
                        }
                    },
                    enabled = true,
                )
            }

            ThemeSwatchLikeButton(
                icon = Icons.Casino,
                text = stringResource(Res.string.random_text),
                selected = random,
                onCheckedChange = {
                    if (it) {
                        save {
                            copy(
                                themeRandom = true,
                                themeDynamic = false
                            )
                        }
                    } else {
                        ThemeUtils.randomizeHue()
                    }
                },
                enabled = true,
            )
        }

        if (BuildKonfig.DEBUG && !PlatformStuff.isTv) {
            Text(
                text = stringResource(Res.string.appwidget_alpha) +
                        ": ${"%.0f".format(alpha * 100)}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = alpha,
                onValueChange = { value -> save { copy(themeAlpha = value) } },
                valueRange = MainPrefs.PREF_MIN_ALPHA..1f,
                steps = 9,
            )
        }

        if (enableExperimental && !PlatformStuff.isTv) {
            Column(
                modifier = Modifier
                    .align(Alignment.Start)
            ) {
                Text(
                    text = "ⓘ " + stringResource(Res.string.experimental) + ":",
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                )

                if (PlatformStuff.supportsBlur) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
//                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.blur),
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        LabeledCheckbox(
                            text = stringResource(Res.string.blur_main_window),
                            checked = themeAttributes.blurMainWindow,
                            enabled = true,
                            maxLines = 1,
                            onCheckedChange = {
                                val newState = !themeAttributes.blurMainWindow
                                save {
                                    copy(
                                        themeBlurMainWindow = newState,
                                        themeAlpha = if (themeAlpha == 1f && newState)
                                            MainPrefs.PREF_MID_ALPHA
                                        else
                                            themeAlpha
                                    )
                                }
                            },
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                        )

                        LabeledCheckbox(
                            text = stringResource(Res.string.blur_sub_window),
                            checked = themeAttributes.blurSubWindow,
                            enabled = true,
                            maxLines = 1,
                            onCheckedChange = {
                                val newState = !themeAttributes.blurSubWindow
                                save {
                                    copy(themeBlurSubWindow = newState)
                                }
                            },
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                        )
                    }
                }

                Text(stringResource(Res.string.appwidget_alpha) + ": $alphaIntPercent%")
                Slider(
                    value = alpha,
                    onValueChange = { value ->
                        save {
                            copy(
                                themeAlpha = value,
                                themeBlurMainWindow = value != 1f && themeBlurMainWindow,
                            )
                        }
                    },
                    valueRange = MainPrefs.PREF_MIN_ALPHA..1f,
                )

                Text(
                    text = stringResource(Res.string.alpha_notice) + "\n" + stringResource(Res.string.blur_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                )
            }
        }
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

@Composable
private fun PaletteStyle.Label() {
    when (this) {
        PaletteStyle.TonalSpot -> Text(stringResource(Res.string.palette_tonal_spot))
        PaletteStyle.Expressive -> Text(stringResource(Res.string.palette_expressive))
        PaletteStyle.Vibrant -> Text(stringResource(Res.string.palette_vibrant))
        PaletteStyle.Cmf -> Text(stringResource(Res.string.palette_cmf))
    }
}

@Composable
private fun ThemeSwatch(
    previewColors: Triple<Color, Color, Color>,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalToggleButton(
        checked = selected,
        onCheckedChange = {
            if (it)
                onClick()
        },
        contentPadding = PaddingValues.Zero,
        enabled = enabled,
        modifier = modifier
            .size(64.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .align(Alignment.TopStart)
                    .background(previewColors.first)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth(0.5f)
                    .align(Alignment.TopEnd)
                    .background(previewColors.second)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth(0.5f)
                    .align(Alignment.BottomEnd)
                    .background(previewColors.third)
            )

            if (selected) {
                Icon(
                    imageVector = Icons.CheckCircleFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        )
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        )
                        .align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun ThemeSwatchLikeButton(
    icon: ImageVector,
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalToggleButton(
        checked = selected,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (!selected) icon else Icons.CheckCircleFilled,
                contentDescription = null
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                maxLines = 2,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(max = 96.dp)
            )
        }
    }
}

@Composable
fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Precompute the rainbow gradient stops once
    val step = 15
    val stops = remember {
        (0..360 step step).map { h ->
            Color(ThemeUtils.getThemeColor(hue = h.toFloat(), tone = 70.0))
        }
    }

    val rainbowBrush = remember {
        Brush.horizontalGradient(stops)
    }

    var internalHue by remember(hue) { mutableFloatStateOf(hue) }
    val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
    val thumbColor by remember(internalHue / step) {
        mutableStateOf(
            stops[(internalHue / step).toInt().coerceIn(0, stops.size - 1)]
        )
    }
    val colors = SliderDefaults.colors(thumbColor = thumbColor)

    Slider(
        value = internalHue,
        enabled = enabled,
        onValueChange = { internalHue = it },
        onValueChangeFinished = { onHueChange(internalHue) },
        valueRange = 0f..360f,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        track = { sliderState ->
            val height = 32.dp

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(CircleShape)
                    .background(
                        rainbowBrush,
                        alpha = if (enabled) 1f else 0.5f
                    )
            )
        },
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                enabled = enabled,
                thumbSize = DpSize(16.dp, 56.dp),
                modifier = Modifier
                    .border(width = 3.dp, color = colors.activeTickColor, shape = CircleShape)
            )
        }
    )
}
