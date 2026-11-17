package dev.etorix.panoscrobbler.discordrpc

expect object DiscordRpcPlatform {
    suspend fun update(activity: DiscordActivity): Boolean
}
