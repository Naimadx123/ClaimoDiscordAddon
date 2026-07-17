package zone.vao.claimoDiscordAddon.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.claimoDiscordAddon.storage.StorageConfig
import zone.vao.claimoDiscordAddon.storage.StorageType
import java.io.File

class ConfigLoader(private val plugin: JavaPlugin) {

    fun load(): AddonConfiguration {
        saveDefault("config.yml")
        saveDefault("messages.yml")

        val main = YamlConfiguration.loadConfiguration(file("config.yml"))
        val messages = YamlConfiguration.loadConfiguration(file("messages.yml"))
        val bot = main.getConfigurationSection("bot")
        val link = main.getConfigurationSection("link")
        val discord = main.getConfigurationSection("discord")

        return AddonConfiguration(
            token = bot?.getString("token")?.trim() ?: "",
            guildId = bot?.getString("guild-id")?.trim()?.toLongOrNull() ?: 0L,
            activity = bot?.getString("activity") ?: "",
            presenceIntent = bot?.getBoolean("intents.presences", false) ?: false,
            membersIntent = bot?.getBoolean("intents.members", false) ?: false,
            codeLength = (link?.getInt("code-length", 6) ?: 6).coerceIn(4, 12),
            codeExpirySeconds = (link?.getLong("code-expiry-seconds", 300L) ?: 300L).coerceAtLeast(30L),
            linkCommandName = discord?.getString("link-command-name", "link")?.trim() ?: "link",
            linkCommandDescription = discord?.getString("link-command-description", "Link your account.") ?: "Link your account.",
            linkOptionName = discord?.getString("link-option-name", "code")?.trim() ?: "code",
            linkOptionDescription = discord?.getString("link-option-description", "Your in-game code.") ?: "Your in-game code.",
            discordLinkSuccess = discord?.getString("link-success", "Linked to **%player%**.") ?: "Linked to **%player%**.",
            discordLinkInvalid = discord?.getString("link-invalid", "Invalid or expired code.") ?: "Invalid or expired code.",
            discordLinkTaken = discord?.getString("link-taken", "Already linked to someone else.") ?: "Already linked to someone else.",
            discordCommandReply = discord?.getString("command-reply", "Recorded! (%used%/%amount%)") ?: "Recorded! (%used%/%amount%)",
            storage = parseStorage(main.getConfigurationSection("storage")),
            messages = parseMessages(messages),
        )
    }

    private fun parseStorage(section: ConfigurationSection?): StorageConfig = StorageConfig(
        type = parseStorageType(section?.getString("type")),
        host = section?.getString("host") ?: "localhost",
        port = section?.getInt("port", 3306) ?: 3306,
        database = section?.getString("database") ?: "claimodiscord",
        username = section?.getString("username") ?: "",
        password = section?.getString("password") ?: "",
        tablePrefix = section?.getString("table-prefix") ?: "claimodiscord_",
        poolSize = (section?.getInt("pool-size", 10) ?: 10).coerceAtLeast(1),
    )

    private fun parseStorageType(value: String?): StorageType = when (value?.trim()?.lowercase()) {
        "sqlite" -> StorageType.SQLITE
        "mysql" -> StorageType.MYSQL
        "mariadb" -> StorageType.MARIADB
        "postgresql", "postgres" -> StorageType.POSTGRESQL
        else -> StorageType.YAML
    }

    private fun parseMessages(section: ConfigurationSection?): Messages {
        val prefix = section?.getString("prefix") ?: ""
        val raw = buildMap {
            section?.getKeys(false)
                ?.filter { it != "prefix" }
                ?.forEach { key -> section.getString(key)?.let { put(key, it) } }
        }
        return Messages(prefix, raw)
    }

    private fun saveDefault(name: String) {
        if (!file(name).exists()) plugin.saveResource(name, false)
    }

    private fun file(name: String) = File(plugin.dataFolder, name)
}
