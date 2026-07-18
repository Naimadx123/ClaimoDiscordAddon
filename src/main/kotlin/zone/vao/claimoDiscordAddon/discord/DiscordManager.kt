package zone.vao.claimoDiscordAddon.discord

import com.google.gson.JsonParser
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon
import zone.vao.claimoDiscordAddon.config.AddonConfiguration
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
        guild.updateCommands().addCommands(specs.map { Commands.slash(it.name, it.description) }).queue()
    }

    fun postLinkPanel(channelId: Long): CompletableFuture<Boolean> {
        val channel = jda?.getTextChannelById(channelId) ?: return CompletableFuture.completedFuture(false)
        val config = plugin.configuration
        val embed = EmbedBuilder()
            .setColor(config.panelEmbedColor)
            .setTitle(config.panelEmbedTitle)
            .setDescription(config.panelEmbedDescription)
            .build()
        val button = Button.primary(LINK_ID, config.linkButtonLabel)
        return channel.sendMessageEmbeds(embed).setComponents(ActionRow.of(button)).submit()
            .handle { _, error -> error == null }
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
        val spec = customCommands[event.name.lowercase()] ?: return
        plugin.linkStorage.incrementCommand(spec.name, event.user.idLong)
        val used = plugin.linkStorage.commandUses(spec.name, event.user.idLong)
        val reply = plugin.configuration.discordCommandReply
            .replace("%used%", used.toString())
            .replace("%amount%", spec.amount.toString())
        event.reply(reply).setEphemeral(true).queue()
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.componentId != LINK_ID) return
        val config = plugin.configuration
        val codeInput = TextInput.create(MODAL_CODE_ID, TextInputStyle.SHORT)
            .setPlaceholder(config.linkModalFieldPlaceholder)
            .setMinLength(1)
            .setMaxLength(32)
            .build()
        val modal = Modal.create(LINK_ID, config.linkModalTitle)
            .addComponents(Label.of(config.linkModalFieldLabel, codeInput))
            .build()
        event.replyModal(modal).queue()
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        if (event.modalId != LINK_ID) return
        val code = event.getValue(MODAL_CODE_ID)?.asString.orEmpty()
        val discordId = event.user.idLong
        val name = event.user.effectiveName
        val user = event.user
        event.deferReply(true).queue()
        plugin.discordExecutor.execute {
            val config = plugin.configuration
            val uuid = plugin.linkCodes.redeem(code)
            when {
                uuid == null -> event.hook.sendMessage(config.discordLinkInvalid).setEphemeral(true).queue()
                plugin.linkStorage.findByDiscordId(discordId)?.let { it.uuid != uuid } == true ->
                    event.hook.sendMessage(config.discordLinkTaken).setEphemeral(true).queue()
                plugin.linkStorage.loadProfile(uuid).discordId?.let { it != discordId } == true ->
                    event.hook.sendMessage(config.discordLinkAlready).setEphemeral(true).queue()
                else -> {
                    plugin.linkStorage.saveProfile(DiscordProfile(uuid, discordId))
                    plugin.notifyLinked(uuid, name)
                    val minecraftName = plugin.server.getOfflinePlayer(uuid).name ?: uuid.toString()
                    event.hook.sendMessageEmbeds(linkEmbed(config, user, name, minecraftName))
                        .setEphemeral(true).queue()
                }
            }
        }
    }

    private fun linkEmbed(config: AddonConfiguration, user: User, discordName: String, minecraftName: String): MessageEmbed {
        val builder = EmbedBuilder()
            .setColor(config.linkEmbedColor)
            .setTitle(config.linkEmbedTitle)
            .setDescription(config.discordLinkSuccess.replace("%player%", discordName))
            .addField("Minecraft", minecraftName, true)
            .addField("Discord", user.asMention, true)
            .setThumbnail(user.effectiveAvatarUrl)
        if (config.linkEmbedFooter.isNotBlank()) builder.setFooter(config.linkEmbedFooter)
        return builder.build()
    }

    private companion object {
        const val LINK_ID = "claimodiscord:link"
        const val MODAL_CODE_ID = "code"
    }
}
