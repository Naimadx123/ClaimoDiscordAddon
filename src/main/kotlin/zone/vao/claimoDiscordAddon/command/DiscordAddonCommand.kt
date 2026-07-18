package zone.vao.claimoDiscordAddon.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon
import zone.vao.claimoDiscordAddon.config.Messages
import zone.vao.claimoDiscordAddon.discord.DiscordProfile
import zone.vao.claimoDiscordAddon.storage.LinkStorage
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Suppress("UnstableApiUsage")
class DiscordAddonCommand(private val plugin: ClaimoDiscordAddon) {

    fun build(commandName: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(commandName)
            .executes { ctx -> messages().send(ctx.source.sender, "cmd-usage"); Command.SINGLE_SUCCESS }
            .then(
                Commands.literal("link")
                    .requires { it.sender.hasPermission(LINK_PERMISSION) }
                    .executes { ctx -> handleLink(ctx.source.sender); Command.SINGLE_SUCCESS }
            )
            .then(
                Commands.literal("unlink")
                    .requires { it.sender.hasPermission(LINK_PERMISSION) }
                    .executes { ctx -> handleUnlink(ctx.source.sender); Command.SINGLE_SUCCESS }
            )
            .then(
                Commands.literal("list")
                    .requires { it.sender.hasPermission(LINK_PERMISSION) }
                    .executes { ctx -> handleList(ctx.source.sender); Command.SINGLE_SUCCESS }
            )
            .then(
                Commands.literal("status")
                    .requires { it.sender.hasPermission(ADMIN_PERMISSION) }
                    .executes { ctx -> handleStatus(ctx.source.sender); Command.SINGLE_SUCCESS }
            )
            .then(
                Commands.literal("reload")
                    .requires { it.sender.hasPermission(ADMIN_PERMISSION) }
                    .executes { ctx -> handleReload(ctx.source.sender); Command.SINGLE_SUCCESS }
            )
            .then(
                Commands.literal("panel")
                    .requires { it.sender.hasPermission(ADMIN_PERMISSION) }
                    .executes { ctx -> handlePanel(ctx.source.sender, null); Command.SINGLE_SUCCESS }
                    .then(
                        Commands.argument("channel", StringArgumentType.word())
                            .executes { ctx ->
                                handlePanel(ctx.source.sender, StringArgumentType.getString(ctx, "channel"))
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("set")
                    .requires { it.sender.hasPermission(ADMIN_PERMISSION) }
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder ->
                                val prefix = builder.remaining.lowercase()
                                plugin.server.onlinePlayers
                                    .map { it.name }
                                    .filter { it.lowercase().startsWith(prefix) }
                                    .forEach(builder::suggest)
                                builder.buildFuture()
                            }
                            .then(
                                Commands.argument("discord-id", StringArgumentType.word())
                                    .executes { ctx ->
                                        handleSet(
                                            ctx.source.sender,
                                            StringArgumentType.getString(ctx, "player"),
                                            StringArgumentType.getString(ctx, "discord-id"),
                                        )
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
            .build()

    private fun handleLink(sender: CommandSender) {
        val player = sender as? Player ?: return messages().send(sender, "cmd-players-only")
        val code = plugin.linkCodes.create(player.uniqueId)
        messages().send(
            sender, "cmd-link-created",
            Placeholder.unparsed("code", code),
            Placeholder.unparsed("expiry", humanizeSeconds(plugin.configuration.codeExpirySeconds)),
        )
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
        val state = if (plugin.discord.isReady()) "<green>online</green>" else "<red>offline</red>"
        messages().send(
            sender, "cmd-status",
            Placeholder.parsed("state", state),
            Placeholder.unparsed("guild", plugin.configuration.guildId.toString()),
        )
    }

    private fun handlePanel(sender: CommandSender, channelArg: String?) {
        val channelId = channelArg?.trim()?.toLongOrNull() ?: plugin.configuration.panelChannelId
        if (channelId == 0L) return messages().send(sender, "cmd-panel-usage")
        plugin.discord.postLinkPanel(channelId).whenComplete { ok, _ ->
            onMain { messages().send(sender, if (ok == true) "cmd-panel-sent" else "cmd-panel-failed") }
        }
    }

    private fun handleSet(sender: CommandSender, playerName: String, discordIdArg: String) {
        val uuid = resolveUuid(playerName)
            ?: return messages().send(sender, "cmd-player-not-found", Placeholder.parsed("player", playerName))
        val discordId = discordIdArg.trim().toLongOrNull() ?: return messages().send(sender, "cmd-set-usage")
        runStorage { storage().saveProfile(DiscordProfile(uuid, discordId)) } then {
            messages().send(
                sender, "cmd-set-success",
                Placeholder.parsed("player", playerName),
                Placeholder.unparsed("discord_id", discordId.toString()),
            )
        }
    }

    private fun handleReload(sender: CommandSender) {
        runCatching { plugin.reloadConfiguration() }
            .onSuccess { messages().send(sender, "cmd-reloaded") }
            .onFailure { messages().send(sender, "cmd-reload-failed", Placeholder.parsed("error", it.message ?: "unknown")) }
    }

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
        const val LINK_PERMISSION = "claimodiscord.link"
        const val ADMIN_PERMISSION = "claimodiscord.admin"
    }
}
