package dev.etorix.panoscrobbler.utils

expect object BugReportUtils {
    suspend fun saveLogsToFile(logFile: PlatformFile)
}