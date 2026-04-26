package dev.etorix.panoscrobbler.pref

import android.app.PendingIntent
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.etorix.panoscrobbler.MasterSwitchQS
import dev.etorix.panoscrobbler.R
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.ui.PanoSnackbarVisuals
import dev.etorix.panoscrobbler.utils.AndroidStuff
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.widget.ChartsWidgetConfigActivity
import dev.etorix.panoscrobbler.widget.ChartsWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.grant_notification_access
import pano_scrobbler.composeapp.generated.resources.persistent_noti_desc
import pano_scrobbler.composeapp.generated.resources.persistent_noti_oems
import pano_scrobbler.composeapp.generated.resources.pref_master
import pano_scrobbler.composeapp.generated.resources.pref_master_qs_add
import pano_scrobbler.composeapp.generated.resources.pref_master_qs_already_addded
import pano_scrobbler.composeapp.generated.resources.pref_noti
import pano_scrobbler.composeapp.generated.resources.pref_offline_info
import pano_scrobbler.composeapp.generated.resources.pref_widget_charts
import pano_scrobbler.composeapp.generated.resources.scrobbler_off
import pano_scrobbler.composeapp.generated.resources.scrobbler_on
import pano_scrobbler.composeapp.generated.resources.show_persistent_noti

actual object PlatformSpecificPrefs {
    actual fun prefQuickSettings(filteredItem: FilteredItem, scrobblerEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PlatformStuff.isTv) {
            filteredItem("master_qs_add", Res.string.pref_master_qs_add, null) { title ->
                val scrobblerEnabledText =
                    stringResource(if (scrobblerEnabled) Res.string.scrobbler_on else Res.string.scrobbler_off)
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                TextPref(
                    text = title,
                    onClick = {
                        val statusBarManager =
                            context.getSystemService(StatusBarManager::class.java)
                                ?: return@TextPref
                        statusBarManager.requestAddTileService(
                            ComponentName(context, MasterSwitchQS::class.java),
                            scrobblerEnabledText,
                            Icon.createWithResource(context, R.drawable.vd_noti),
                            context.mainExecutor
                        ) { result ->
                            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                scope.launch {
                                    Stuff.globalSnackbarFlow.emit(
                                        PanoSnackbarVisuals(
                                            message = getString(Res.string.pref_master_qs_already_addded),
                                            isError = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestPinWidget(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val pi = PendingIntent.getActivity(
                context,
                30,
                Intent(context, ChartsWidgetConfigActivity::class.java)
                    .apply { putExtra(Stuff.EXTRA_PINNED, true) },
                AndroidStuff.updateCurrentOrMutable
            )

            val myProvider =
                ComponentName(context, ChartsWidgetProvider::class.java)
            appWidgetManager.requestPinAppWidget(myProvider, null, pi)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun launchNotificationsActivity(context: Context) {
        val intent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent)
    }

    actual fun prefChartsWidget(filteredItem: FilteredItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !PlatformStuff.isTv) {
            filteredItem("widget", Res.string.pref_widget_charts, null) { title ->
                val context = LocalContext.current

                TextPref(
                    text = title,
                    onClick = {
                        requestPinWidget(context)
                    }
                )
            }
        }
    }

    actual fun prefNotifications(filteredItem: FilteredItem, notiPersistent: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !PlatformStuff.isTv) {
            filteredItem("notifications", Res.string.pref_noti, null) { title ->
                val context = LocalContext.current
                TextPref(
                    text = title,
                    onClick = {
                        launchNotificationsActivity(context)
                    }
                )
            }
        }

        if (AndroidStuff.canShowPersistentNotiIfEnabled && !PlatformStuff.isTv) {
            filteredItem(
                MainPrefs::notiPersistent.name,
                Res.string.show_persistent_noti,
                null
            ) { title ->
                SwitchPref(
                    text = title,
                    summary = stringResource(
                        Res.string.persistent_noti_desc,
                        stringResource(Res.string.persistent_noti_oems)
                    ),
                    value = notiPersistent,
                    copyToSave = {
                        copy(notiPersistent = it)
                    }
                )
            }
        }
    }

    actual fun prefAutostart(filteredItem: FilteredItem) {}

    actual fun discordRpc(filteredItem: FilteredItem, onNavigate: (PanoRoute) -> Unit) {
        // no-op
    }

    actual fun tidalSteelSeries(filteredItem: FilteredItem, enabled: Boolean) {
    }

    actual fun deezerApi(filteredItem: FilteredItem, enabled: Boolean) {
    }

    actual fun prefScrobbler(
        filteredItem: FilteredItem,
        scrobblerEnabled: Boolean,
        nlsEnabled: Boolean,
        onNavigate: (PanoRoute) -> Unit,
    ) {
        filteredItem(MainPrefs::scrobblerEnabled.name, Res.string.pref_master, null) { title ->
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            SwitchPref(
                text = title,
                summary = if (!nlsEnabled)
                    stringResource(Res.string.grant_notification_access)
                else
                    stringResource(Res.string.pref_offline_info),
                value = scrobblerEnabled && nlsEnabled,
                copyToSave = {
                    if (!nlsEnabled) {
                        onNavigate(PanoRoute.Onboarding)
                        this
                    } else {
                        scope.launch(Dispatchers.IO) {
                            MasterSwitchQS.requestListeningState(context)
                            if (it) {
                                AndroidStuff.requestRebindFromContentProvider(context.contentResolver)
                            }
                        }

                        copy(scrobblerEnabled = it)
                    }
                }
            )
        }
    }
}