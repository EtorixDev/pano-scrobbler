package dev.etorix.panoscrobbler.pref

import dev.etorix.panoscrobbler.navigation.PanoRoute

expect object PlatformSpecificPrefs {
    fun prefNotifications(filteredItem: FilteredItem, notiPersistent: Boolean)

    fun prefScrobbler(
        filteredItem: FilteredItem,
        scrobblerEnabled: Boolean,
        nlsEnabled: Boolean,
        onNavigate: (PanoRoute) -> Unit
    )

    fun prefQuickSettings(filteredItem: FilteredItem, scrobblerEnabled: Boolean)

    fun prefChartsWidget(filteredItem: FilteredItem)

    fun prefAutostart(filteredItem: FilteredItem)
    fun discordRpc(filteredItem: FilteredItem, onNavigate: (PanoRoute) -> Unit)
    fun tidalSteelSeries(filteredItem: FilteredItem, enabled: Boolean)

    fun deezerApi(filteredItem: FilteredItem, enabled: Boolean)
}