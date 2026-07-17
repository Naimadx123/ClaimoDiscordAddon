package zone.vao.claimoDiscordAddon.storage

import org.bukkit.configuration.file.YamlConfiguration
import zone.vao.claimoDiscordAddon.discord.DiscordProfile
import java.io.File
import java.util.UUID

class YamlLinkStorage(private val file: File) : LinkStorage {

    private val lock = Any()
    private val yaml: YamlConfiguration
    private val messages = HashMap<Long, Long>()
    private val commands = HashMap<String, HashMap<Long, Long>>()
    private var dirty = false

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        yaml = YamlConfiguration.loadConfiguration(file)
        loadStats()
    }

    override fun loadProfile(uuid: UUID): DiscordProfile = synchronized(lock) {
        val discordId = yaml.getString("players.$uuid.discord-id")?.toLongOrNull()
        DiscordProfile(uuid, discordId)
    }

    override fun saveProfile(profile: DiscordProfile): Unit = synchronized(lock) {
        val path = "players.${profile.uuid}"
        yaml.set(path, null)
        profile.discordId?.let { yaml.set("$path.discord-id", it.toString()) }
        persistLocked()
    }

    override fun findByDiscordId(discordId: Long): DiscordProfile? = synchronized(lock) {
        val players = yaml.getConfigurationSection("players") ?: return null
        for (key in players.getKeys(false)) {
            if (players.getString("$key.discord-id")?.toLongOrNull() == discordId) {
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                return DiscordProfile(uuid, discordId)
            }
        }
        return null
    }

    override fun incrementMessages(discordId: Long): Unit = synchronized(lock) {
        messages.merge(discordId, 1L, Long::plus)
        dirty = true
    }

    override fun messages(discordId: Long): Long = synchronized(lock) { messages[discordId] ?: 0L }

    override fun incrementCommand(command: String, discordId: Long): Unit = synchronized(lock) {
        commands.getOrPut(command.lowercase()) { HashMap() }.merge(discordId, 1L, Long::plus)
        dirty = true
    }

    override fun commandUses(command: String, discordId: Long): Long =
        synchronized(lock) { commands[command.lowercase()]?.get(discordId) ?: 0L }

    override fun flush(): Unit = synchronized(lock) {
        if (dirty) persistLocked()
    }

    override fun close(): Unit = synchronized(lock) { persistLocked() }

    private fun loadStats() {
        yaml.getConfigurationSection("stats.messages")?.let { section ->
            for (key in section.getKeys(false)) key.toLongOrNull()?.let { messages[it] = section.getLong(key) }
        }
        yaml.getConfigurationSection("stats.commands")?.let { commandsSection ->
            for (command in commandsSection.getKeys(false)) {
                val users = commandsSection.getConfigurationSection(command) ?: continue
                val map = commands.getOrPut(command.lowercase()) { HashMap() }
                for (key in users.getKeys(false)) key.toLongOrNull()?.let { map[it] = users.getLong(key) }
            }
        }
    }

    private fun persistLocked() {
        yaml.set("stats", null)
        messages.forEach { (discordId, count) -> yaml.set("stats.messages.$discordId", count) }
        commands.forEach { (command, users) ->
            users.forEach { (discordId, count) -> yaml.set("stats.commands.$command.$discordId", count) }
        }
        runCatching { yaml.save(file) }
        dirty = false
    }
}
