package zone.vao.claimoDiscordAddon.hook

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon

@Suppress("unused")
class DiscordExpansion(private val plugin: ClaimoDiscordAddon) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "claimodiscord"

    override fun getAuthor(): String = plugin.pluginMeta.authors.firstOrNull() ?: "Naimad (dc: 4g0)"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        DiscordPlaceholders.resolve(plugin, player, params)
}
