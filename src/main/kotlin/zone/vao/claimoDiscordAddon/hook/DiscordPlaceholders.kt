package zone.vao.claimoDiscordAddon.hook

import org.bukkit.OfflinePlayer
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DiscordPlaceholders {

    val GLOBAL = listOf("bot_online", "guild_id")
    val SIMPLE = listOf("linked", "id", "name", "messages", "member", "booster", "status")
    val PREFIXES = listOf("role", "command")

    private const val CACHE_MILLIS = 5_000L

    private val cache = ConcurrentHashMap<UUID, Cached>()

    fun resolve(plugin: ClaimoDiscordAddon, player: OfflinePlayer?, params: String): String? {
        global(plugin, params)?.let { return it }
        val (prefix, argument) = split(params) ?: return simple(plugin, player, params)
        return value(plugin, player, prefix, argument)
    }

    fun global(plugin: ClaimoDiscordAddon, params: String): String? = when (params.lowercase()) {
        "bot_online" -> bool(plugin, plugin.discord.isReady())
        "guild_id" -> plugin.configuration.guildId.toString()
        else -> null
    }

    fun simple(plugin: ClaimoDiscordAddon, player: OfflinePlayer?, params: String): String? {
        val uuid = player?.uniqueId ?: return null
        val id = discordId(plugin, uuid)
        return when (params.lowercase()) {
            "linked" -> bool(plugin, id != null)
            "id" -> id?.toString() ?: ""
            "name" -> id?.let { plugin.discord.cachedMember(it)?.effectiveName } ?: ""
            "messages" -> (id?.let { plugin.linkStorage.messages(it) } ?: 0L).toString()
            "member" -> bool(plugin, id != null && plugin.discord.cachedMember(id) != null)
            "booster" -> bool(plugin, id != null && plugin.discord.cachedMember(id)?.timeBoosted != null)
            "status" -> id?.let { plugin.discord.customStatus(it) } ?: ""
            else -> null
        }
    }

    fun value(plugin: ClaimoDiscordAddon, player: OfflinePlayer?, prefix: String, argument: String): String? {
        val uuid = player?.uniqueId ?: return null
        val id = discordId(plugin, uuid)
        val token = argument.trim()
        if (token.isEmpty()) return null
        return when (prefix.lowercase()) {
            "role" -> bool(plugin, id != null && hasRole(plugin, id, token))
            "command" -> (id?.let { plugin.linkStorage.commandUses(token, it) } ?: 0L).toString()
            else -> null
        }
    }

    private fun hasRole(plugin: ClaimoDiscordAddon, discordId: Long, token: String): Boolean {
        val member = plugin.discord.cachedMember(discordId) ?: return false
        return member.roles.any { it.id == token || it.name.equals(token, ignoreCase = true) }
    }

    private fun discordId(plugin: ClaimoDiscordAddon, uuid: UUID): Long? {
        val entry = cache[uuid]
        val now = System.currentTimeMillis()
        if (entry == null || now - entry.at > CACHE_MILLIS) {
            cache[uuid] = Cached(entry?.discordId, now)
            plugin.discordExecutor.execute {
                val id = runCatching { plugin.linkStorage.loadProfile(uuid).discordId }.getOrElse { entry?.discordId }
                cache[uuid] = Cached(id, System.currentTimeMillis())
            }
        }
        return entry?.discordId
    }

    private fun bool(plugin: ClaimoDiscordAddon, value: Boolean): String =
        if (value) plugin.configuration.placeholderTrue else plugin.configuration.placeholderFalse

    private fun split(params: String): Pair<String, String>? {
        for (prefix in PREFIXES) {
            if (params.length > prefix.length + 1 &&
                params.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true) &&
                params[prefix.length] == '_'
            ) {
                return prefix to params.substring(prefix.length + 1)
            }
        }
        return null
    }

    private class Cached(val discordId: Long?, val at: Long)
}
