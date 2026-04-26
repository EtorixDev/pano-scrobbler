package dev.etorix.panoscrobbler.themes

import dev.etorix.panoscrobbler.themes.colors.Theme009788
import dev.etorix.panoscrobbler.themes.colors.Theme01BDD6
import dev.etorix.panoscrobbler.themes.colors.Theme03A9F5
import dev.etorix.panoscrobbler.themes.colors.Theme109D58
import dev.etorix.panoscrobbler.themes.colors.Theme3F51B5
import dev.etorix.panoscrobbler.themes.colors.Theme4385F6
import dev.etorix.panoscrobbler.themes.colors.Theme683AB7
import dev.etorix.panoscrobbler.themes.colors.Theme8CC24A
import dev.etorix.panoscrobbler.themes.colors.Theme9E28B2
import dev.etorix.panoscrobbler.themes.colors.ThemeB19BDB
import dev.etorix.panoscrobbler.themes.colors.ThemeCDDC39
import dev.etorix.panoscrobbler.themes.colors.ThemeDD4337
import dev.etorix.panoscrobbler.themes.colors.ThemeE8D1A8
import dev.etorix.panoscrobbler.themes.colors.ThemeEA1E63
import dev.etorix.panoscrobbler.themes.colors.ThemeF6B300
import dev.etorix.panoscrobbler.themes.colors.ThemeFBBC6F
import dev.etorix.panoscrobbler.themes.colors.ThemeFDC5C6
import dev.etorix.panoscrobbler.themes.colors.ThemeFE5722
import dev.etorix.panoscrobbler.themes.colors.ThemeFF748F
import dev.etorix.panoscrobbler.themes.colors.ThemeFF9803
import dev.etorix.panoscrobbler.themes.colors.ThemeFFEB3C

enum class DayNightMode {
    SYSTEM, LIGHT, DARK
}

enum class ContrastMode {
    LOW, MEDIUM, HIGH
}

object ThemeUtils {
    val themesMap by lazy {
        val associateBy = arrayOf(
            Theme4385F6,
            Theme03A9F5,
            Theme01BDD6,
            Theme3F51B5,
            Theme683AB7,
            Theme9E28B2,
            ThemeB19BDB,
            Theme009788,
            Theme109D58,
            Theme8CC24A,
            ThemeCDDC39,
            ThemeFFEB3C,
            ThemeF6B300,
            ThemeFBBC6F,
            ThemeFF9803,
            ThemeFE5722,
            ThemeDD4337,
            ThemeEA1E63,
            ThemeFF748F,
            ThemeFDC5C6,
            ThemeE8D1A8
        ).associateBy { it.name }
        associateBy
    }

    val defaultTheme
        get() = ThemeEA1E63
    val defaultThemeName = ThemeEA1E63.name

    fun themeNameToObject(name: String) = themesMap.getOrDefault(name, defaultTheme)
}
