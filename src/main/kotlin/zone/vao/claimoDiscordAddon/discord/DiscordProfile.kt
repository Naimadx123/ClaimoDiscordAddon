package zone.vao.claimoDiscordAddon.discord

import java.util.UUID

data class DiscordProfile(
    val uuid: UUID,
    val discordId: Long? = null,
) {
    fun isLinked(): Boolean = discordId != null
}
