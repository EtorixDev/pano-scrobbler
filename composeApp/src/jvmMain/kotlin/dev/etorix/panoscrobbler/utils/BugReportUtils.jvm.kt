package dev.etorix.panoscrobbler.utils

import dev.etorix.panoscrobbler.logger.JavaUtilFileLogger


actual object BugReportUtils {
    actual suspend fun saveLogsToFile(logFile: PlatformFile) {
        logFile.writeAppend { output ->
            JavaUtilFileLogger.mergeLogFilesTo(output)
        }
    }

}