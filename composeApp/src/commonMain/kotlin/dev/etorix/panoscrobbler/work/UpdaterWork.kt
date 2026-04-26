package dev.etorix.panoscrobbler.work

expect object UpdaterWork : CommonWork {
    fun schedule(force: Boolean)
}