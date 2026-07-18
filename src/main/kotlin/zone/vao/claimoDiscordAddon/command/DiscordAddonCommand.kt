package zone.vao.claimoDiscordAddon.command

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon
import zone.vao.claimoDiscordAddon.config.Messages
import zone.vao.claimoDiscordAddon.discord.DiscordProfile
import zone.vao.claimoDiscordAddon.storage.LinkStorage
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DiscordAddonCommand(private val plugin: ClaimoDiscordAddon) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            "link" -> handleLink(sender)
            "unlink" -> handleUnlink(sender)
            "list" -> handleList(sender)
            "status" -> handleStatus(sender)
            "panel" -> handlePanel(sender, args)
            "set" -> handleSet(sender, args)
            "reload" -> handleReload(sender)
            else -> messages().send(sender, "cmd-usage")
        }
        return true
    }

    private fun handleLink(sender: CommandSender) {
        val player = sender as? Player ?: return messages().send(sender, "cmd-players-only")
        val code = plugin.linkCodes.create(player.uniqueId)
        messages().send(
            sender, "cmd-link-created",
            Placeholder.unparsed("code", code),
            Placeholder.unparsed("expiry", humanizeSeconds(plugin.configuration.codeExpirySeconds)),
        )
    }

    private fun handlePanel(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return messages().send(sender, "cmd-no-permission")
        val channelId = args.getOrNull(1)?.trim()?.toLongOrNull() ?: plugin.configuration.panelChannelId
        if (channelId == 0L) return messages().send(sender, "cmd-panel-usage")
        plugin.discord.postLinkPanel(channelId).whenComplete { ok, _ ->
            onMain { messages().send(sender, if (ok == true) "cmd-panel-sent" else "cmd-panel-failed") }
        }
    }

    private fun handleUnlink(sender: CommandSender) {
        val player = sender as? Player ?: return messages().send(sender, "cmd-players-only")
        runStorageResult {
            val profile = storage().loadProfile(player.uniqueId)
            val had = profile.isLinked()
            if (had) storage().saveProfile(profile.copy(discordId = null))
            had
        } then { had ->
            messages().send(sender, if (had) "cmd-unlink-success" else "cmd-unlink-none")
        }
    }

    private fun handleList(sender: CommandSender) {
        val player = sender as? Player ?: return messages().send(sender, "cmd-players-only")
        runStorageResult { storage().loadProfile(player.uniqueId).discordId } then { id ->
            if (id == null) messages().send(sender, "cmd-list-none")
            else messages().send(sender, "cmd-list-linked", Placeholder.unparsed("discord_id", id.toString()))
        }
    }

    private fun handleStatus(sender: CommandSender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return messages().send(sender, "cmd-no-permission")
        val state = if (plugin.discord.isReady()) "<green>online</green>" else "<red>offline</red>"
        messages().send(
            sender, "cmd-status",
            Placeholder.parsed("state", state),
            Placeholder.unparsed("guild", plugin.configuration.guildId.toString()),
        )
    }

    private fun handleSet(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return messages().send(sender, "cmd-no-permission")
        if (args.size < 3) return messages().send(sender, "cmd-set-usage")
        val uuid = resolveUuid(args[1]) ?: return messages().send(sender, "cmd-player-not-found", Placeholder.parsed("player", args[1]))
        val discordId = args[2].trim().toLongOrNull() ?: return messages().send(sender, "cmd-set-usage")
        runStorage { storage().saveProfile(DiscordProfile(uuid, discordId)) } then {
            messages().send(
                sender, "cmd-set-success",
                Placeholder.parsed("player", args[1]),
                Placeholder.unparsed("discord_id", discordId.toString()),
            )
        }
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return messages().send(sender, "cmd-no-permission")
        runCatching { plugin.reloadConfiguration() }
            .onSuccess { messages().send(sender, "cmd-reloaded") }
            .onFailure { messages().send(sender, "cmd-reload-failed", Placeholder.parsed("error", it.message ?: "unknown")) }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission(ADMIN_PERMISSION)
        return when (args.size) {
            1 -> (PLAYER_SUBS + if (admin) ADMIN_SUBS else emptyList()).filter { it.startsWith(args[0].lowercase()) }
            2 -> if (admin && args[0].lowercase() == "set") onlinePlayers(args[1]) else emptyList()
            else -> emptyList()
        }
    }

    private fun onlinePlayers(prefix: String): List<String> =
        plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }

    private fun resolveUuid(name: String): UUID? {
        plugin.server.getPlayerExact(name)?.let { return it.uniqueId }
        return plugin.server.getOfflinePlayerIfCached(name)?.uniqueId
    }

    private fun humanizeSeconds(seconds: Long): String = when {
        seconds % 60L == 0L -> "${seconds / 60L} minute(s)"
        else -> "$seconds second(s)"
    }

    private fun messages(): Messages = plugin.configuration.messages
    private fun storage(): LinkStorage = plugin.linkStorage

    private fun onMain(block: () -> Unit) {
        plugin.server.globalRegionScheduler.execute(plugin, Runnable { block() })
    }

    private fun runStorage(block: () -> Unit) = runStorageResult { block() }

    private fun <T> runStorageResult(block: () -> T): Pending<T> =
        Pending(CompletableFuture.supplyAsync({ block() }, plugin.discordExecutor))

    private inner class Pending<T>(private val future: CompletableFuture<T>) {
        infix fun then(block: (T) -> Unit) {
            future.whenComplete { value, error ->
                onMain {
                    if (error != null) plugin.logger.warning("ClaimoDiscord: command storage operation failed: ${error.message}")
                    else block(value)
                }
            }
        }
    }

    private companion object {
        const val ADMIN_PERMISSION = "claimodiscord.admin"
        val PLAYER_SUBS = listOf("link", "unlink", "list")
        val ADMIN_SUBS = listOf("panel", "set", "status", "reload")
    }
}
