package zone.vao.claimoDiscordAddon

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.claimoDiscordAddon.command.DiscordAddonCommand
import zone.vao.claimoDiscordAddon.config.AddonConfiguration
import zone.vao.claimoDiscordAddon.config.ConfigLoader
import zone.vao.claimoDiscordAddon.discord.DiscordManager
import zone.vao.claimoDiscordAddon.link.LinkCodes
import zone.vao.claimoDiscordAddon.requirement.DiscordRequirementTypes
import zone.vao.claimoDiscordAddon.requirement.DiscordRequirements
import zone.vao.claimoDiscordAddon.storage.LinkStorage
import zone.vao.claimoDiscordAddon.storage.StorageFactory
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ClaimoDiscordAddon : JavaPlugin() {

    val discordExecutor = Executors.newCachedThreadPool(DaemonThreadFactory)

    @Volatile
    lateinit var configuration: AddonConfiguration
        private set

    @Volatile
    lateinit var linkStorage: LinkStorage
        private set

    @Volatile
    lateinit var discord: DiscordManager
        private set

    lateinit var linkCodes: LinkCodes
        private set

    private val configLoader = ConfigLoader(this)

    override fun onEnable() {
        configuration = configLoader.load()
        linkStorage = StorageFactory.create(this, configuration.storage)
        linkCodes = LinkCodes(configuration.codeLength, configuration.codeExpirySeconds)
        discord = DiscordManager(this)

        DiscordRequirements.register(this)
        discord.updateCommands(DiscordRequirements.commandSpecs())
        discord.start()

        val command = DiscordAddonCommand(this)
        getCommand("claimodiscord")?.apply {
            setExecutor(command)
            tabCompleter = command
        } ?: logger.warning("Command 'claimodiscord' is missing from plugin.yml; the /claimodiscord command is unavailable.")

        server.asyncScheduler.runAtFixedRate(this, { linkStorage.flush() }, 60L, 60L, TimeUnit.SECONDS)

        logger.info("ClaimoDiscordAddon enabled — ${DiscordRequirementTypes.TYPES.size} requirement types registered (storage: ${configuration.storage.type}).")
    }

    override fun onDisable() {
        if (::discord.isInitialized) runCatching { discord.shutdown() }
        runCatching { DiscordRequirements.unregister() }
        if (::linkStorage.isInitialized) runCatching { linkStorage.close() }
        discordExecutor.shutdownNow()
    }

    fun reloadConfiguration() {
        val old = if (::linkStorage.isInitialized) linkStorage else null
        configuration = configLoader.load()
        linkStorage = StorageFactory.create(this, configuration.storage)
        old?.let { runCatching { it.close() } }
        discord.updateCommands(DiscordRequirements.commandSpecs())
    }

    fun notifyLinked(uuid: UUID, discordName: String) {
        server.globalRegionScheduler.execute(this) {
            val player = server.getPlayer(uuid) ?: return@execute
            configuration.messages.send(player, "cmd-link-success", Placeholder.unparsed("name", discordName))
        }
    }

    private object DaemonThreadFactory : ThreadFactory {
        private val counter = AtomicInteger(1)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "ClaimoDiscord-Worker-${counter.getAndIncrement()}").apply { isDaemon = true }
    }
}
