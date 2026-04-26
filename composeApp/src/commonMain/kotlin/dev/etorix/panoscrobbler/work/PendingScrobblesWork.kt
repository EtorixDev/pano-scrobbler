package dev.etorix.panoscrobbler.work

expect object PendingScrobblesWork : CommonWork {
    fun schedule(force: Boolean)
}