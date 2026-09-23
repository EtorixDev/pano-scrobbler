package dev.etorix.panoscrobbler

import dev.etorix.panoscrobbler.pref.MainPrefs
import dev.etorix.panoscrobbler.themes.PaletteStyle
import dev.etorix.panoscrobbler.themes.ThemeUtils
import hct.Hct
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePrefsTest {
    @Test
    fun savedThemeNameSurvivesHueMigration() {
        val legacy = Json.decodeFromString<MainPrefs>("""{"themeName":"EA1E63"}""")
        val oldHue = Hct.fromInt(0xFFEA1E63.toInt()).hue.toFloat()

        assertEquals(oldHue, legacy.themeHueP, 0.001f)
        assertEquals(oldHue, MainPrefs().updateFromPublicPrefs(legacy.toPublicPrefs()).themeHueP, 0.001f)
        assertEquals(ThemeUtils.DEFAULT_HUE, MainPrefs().themeHueP, 1f)

        val selected = legacy.copy(themeHue = 120f, themeStyle = PaletteStyle.Vibrant.name)
        val restored = Json.decodeFromString<MainPrefs>(Json.encodeToString(selected))

        assertEquals(120f, restored.themeHueP)
        assertEquals(PaletteStyle.Vibrant.name, restored.themeStyle)
    }
}
