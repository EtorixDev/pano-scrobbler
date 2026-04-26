package com.arn.scrobble.utils

expect object BugReportUtils {
    suspend fun saveLogsToFile(logFile: PlatformFile)
}