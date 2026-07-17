package zone.vao.claimoDiscordAddon.storage

import zone.vao.claimoDiscordAddon.discord.DiscordProfile
import java.util.UUID

interface LinkStorage {

    fun loadProfile(uuid: UUID): DiscordProfile

    fun saveProfile(profile: DiscordProfile)

    fun findByDiscordId(discordId: Long): DiscordProfile?

    fun incrementMessages(discordId: Long)

    fun messages(discordId: Long): Long

    fun incrementCommand(command: String, discordId: Long)

    fun commandUses(command: String, discordId: Long): Long

    fun flush()

    fun close()

    companion object {
        const val KIND_MESSAGE = "message"
        const val KIND_COMMAND = "command"
    }
}
