package dev.etorix.panoscrobbler.work


expect object DigestWork : CommonWork {
    fun schedule(
        weeklyDigestTime: Long,
        monthlyDigestTime: Long,
    )
}