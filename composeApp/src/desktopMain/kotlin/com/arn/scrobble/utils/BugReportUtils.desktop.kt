package com.arn.scrobble.utils

import com.arn.scrobble.logger.JavaUtilFileLogger


actual object BugReportUtils {
    actual suspend fun saveLogsToFile(logFile: PlatformFile) {
        logFile.writeAppend { output ->
            JavaUtilFileLogger.mergeLogFilesTo(output)
        }
    }

}