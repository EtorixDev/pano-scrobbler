package com.arn.scrobble.utils

import android.app.ActivityManager
import android.os.Build
import co.touchlab.kermit.Logger
import com.arn.scrobble.logger.JavaUtilFileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

actual object BugReportUtils {

    actual suspend fun saveLogsToFile(logFile: PlatformFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AndroidStuff.getScrobblerExitReasons().let {
                it.take(5).forEachIndexed { index, applicationExitInfo ->
                    Logger.w("${index + 1}. $applicationExitInfo", tag = "exitReasons")
                }
            }
        }

        val command = "logcat -d *:I"

        try {
            withContext(Dispatchers.IO) {
                val process = Runtime.getRuntime().exec(command)
                process.inputStream.use { input ->
                    logFile.overwrite { output ->
                        input.copyTo(output)
                    }
                }
            }

        } catch (e: IOException) {
            Logger.e(e) { "Failed to read logcat output" }
        }

        if (PlatformStuff.mainPrefs.data.map { it.logToFileOnAndroid }.first()) {
            logFile.writeAppend { output ->
                JavaUtilFileLogger.mergeLogFilesTo(output)
            }
        }
    }
}