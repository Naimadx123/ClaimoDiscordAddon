package zone.vao.claimoDiscordAddon.discord

import com.google.gson.JsonParser
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

class DiscordManager(private val plugin: ClaimoDiscordAddon) : ListenerAdapter() {

    data class CustomCommandSpec(val name: String, val description: String, val amount: Long)
    data class PrimaryGuild(val guildId: Long?, val tag: String?)

    @Volatile
    private var jda: JDA? = null

    @Volatile
    private var customCommands: Map<String, CustomCommandSpec> = emptyMap()

    private val http: HttpClient = HttpClient.newHttpClient()

    val guildId: Long get() = plugin.configuration.guildId

    fun start() {
        val config = plugin.configuration
        if (!config.hasToken()) {
            plugin.logger.warning("No Discord bot token configured; Discord requirements will report as unavailable.")
            return
        }
        plugin.server.asyncScheduler.runNow(plugin) {
            runCatching {
                val intents = mutableSetOf(GatewayIntent.GUILD_MESSAGES)
                val cacheFlags = mutableSetOf<CacheFlag>()
                var policy = MemberCachePolicy.DEFAULT
                if (config.membersIntent) {
                    intents += GatewayIntent.GUILD_MEMBERS
                    policy = MemberCachePolicy.ALL
                }
                if (config.presenceIntent) {
                    intents += GatewayIntent.GUILD_PRESENCES
                    cacheFlags += CacheFlag.ACTIVITY
                    if (!config.membersIntent) policy = MemberCachePolicy.ONLINE
                }
                val builder = JDABuilder.create(config.token, intents)
                    .setMemberCachePolicy(policy)
                    .addEventListeners(this)
                if (cacheFlags.isNotEmpty()) builder.enableCache(cacheFlags)
                if (config.activity.isNotBlank()) builder.setActivity(Activity.customStatus(config.activity))
                jda = builder.build()
            }.onFailure { plugin.logger.severe("Failed to start the Discord bot: ${it.message}") }
        }
    }

    fun shutdown() {
        jda?.shutdown()
        jda = null
    }

    fun isReady(): Boolean = jda?.status == JDA.Status.CONNECTED

    fun updateCommands(specs: List<CustomCommandSpec>) {
        customCommands = specs.associateBy { it.name.lowercase() }
        val guild = jda?.getGuildById(guildId) ?: return
        val config = plugin.configuration
        val commands = buildList<SlashCommandData> {
            add(
                Commands.slash(config.linkCommandName, config.linkCommandDescription)
                    .addOption(OptionType.STRING, config.linkOptionName, config.linkOptionDescription, true)
            )
            specs.forEach { add(Commands.slash(it.name, it.description)) }
        }
        guild.updateCommands().addCommands(commands).queue()
    }

    fun retrieveMember(discordId: Long): CompletableFuture<Member?> {
        val guild = jda?.getGuildById(guildId) ?: return CompletableFuture.completedFuture(null)
        return guild.retrieveMemberById(discordId).submit().handle { member, _ -> member }
    }

    fun customStatus(discordId: Long): String? {
        val member = jda?.getGuildById(guildId)?.getMemberById(discordId) ?: return null
        val activity = member.activities.firstOrNull { it.type == Activity.ActivityType.CUSTOM_STATUS } ?: return null
        return activity.name.takeIf { it.isNotBlank() } ?: activity.state
    }

    fun fetchPrimaryGuild(discordId: Long): CompletableFuture<PrimaryGuild?> {
        val config = plugin.configuration
        if (!config.hasToken()) return CompletableFuture.completedFuture(null)
        val request = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/users/$discordId"))
            .header("Authorization", "Bot ${config.token}")
            .header("User-Agent", "ClaimoDiscordAddon (https://claimo.vao.zone, 1.0)")
            .GET()
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            if (response.statusCode() != 200) return@thenApply null
            runCatching {
                val root = JsonParser.parseString(response.body()).asJsonObject
                val node = root.get("primary_guild")?.takeUnless { it.isJsonNull }?.asJsonObject
                    ?: return@runCatching PrimaryGuild(null, null)
                val enabled = node.get("identity_enabled")?.takeUnless { it.isJsonNull }?.asBoolean == true
                if (!enabled) return@runCatching PrimaryGuild(null, null)
                val gid = node.get("identity_guild_id")?.takeUnless { it.isJsonNull }?.asString?.toLongOrNull()
                val tag = node.get("tag")?.takeUnless { it.isJsonNull }?.asString
                PrimaryGuild(gid, tag)
            }.getOrNull()
        }.exceptionally { null }
    }

    override fun onReady(event: ReadyEvent) {
        plugin.logger.info("Discord bot connected as ${event.jda.selfUser.name}.")
        updateCommands(customCommands.values.toList())
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) return
        if (event.guild.idLong != guildId) return
        plugin.linkStorage.incrementMessages(event.author.idLong)
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name.equals(plugin.configuration.linkCommandName, ignoreCase = true)) {
            handleLink(event)
            return
        }
        val spec = customCommands[event.name.lowercase()] ?: return
        plugin.linkStorage.incrementCommand(spec.name, event.user.idLong)
        val used = plugin.linkStorage.commandUses(spec.name, event.user.idLong)
        val reply = plugin.configuration.discordCommandReply
            .replace("%used%", used.toString())
            .replace("%amount%", spec.amount.toString())
        event.reply(reply).setEphemeral(true).queue()
    }

    private fun handleLink(event: SlashCommandInteractionEvent) {
        val code = event.getOption(plugin.configuration.linkOptionName)?.asString.orEmpty()
        val discordId = event.user.idLong
        val name = event.user.effectiveName
        event.deferReply(true).queue()
        plugin.discordExecutor.execute {
            val config = plugin.configuration
            val uuid = plugin.linkCodes.redeem(code)
            val reply = when {
                uuid == null -> config.discordLinkInvalid
                plugin.linkStorage.findByDiscordId(discordId)?.let { it.uuid != uuid } == true -> config.discordLinkTaken
                else -> {
                    plugin.linkStorage.saveProfile(DiscordProfile(uuid, discordId))
                    plugin.notifyLinked(uuid, name)
                    config.discordLinkSuccess.replace("%player%", name)
                }
            }
            event.hook.sendMessage(reply).setEphemeral(true).queue()
        }
    }
}
