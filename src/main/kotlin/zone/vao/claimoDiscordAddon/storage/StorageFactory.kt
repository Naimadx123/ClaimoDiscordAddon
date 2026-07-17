package zone.vao.claimoDiscordAddon.storage

import com.zaxxer.hikari.HikariConfig
import org.bukkit.plugin.Plugin
import java.io.File

object StorageFactory {

    fun create(plugin: Plugin, config: StorageConfig): LinkStorage = when (config.type) {
        StorageType.YAML -> YamlLinkStorage(File(plugin.dataFolder, "links.yml"))
        else -> SqlLinkStorage(buildHikari(plugin, config), config.tablePrefix)
    }

    private fun buildHikari(plugin: Plugin, config: StorageConfig): HikariConfig {
        val hikari = HikariConfig()
        hikari.poolName = "claimodiscord-pool"

        when (config.type) {
            StorageType.SQLITE -> {
                val file = File(plugin.dataFolder, "links.db")
                file.parentFile?.mkdirs()
                hikari.jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
                hikari.driverClassName = "org.sqlite.JDBC"
                hikari.maximumPoolSize = 1
                hikari.connectionInitSql = "PRAGMA busy_timeout=5000"
            }

            StorageType.MYSQL, StorageType.MARIADB -> {
                hikari.jdbcUrl = "jdbc:mysql://${config.host}:${config.port}/${config.database}"
                hikari.driverClassName = "com.mysql.cj.jdbc.Driver"
                hikari.username = config.username
                hikari.password = config.password
                hikari.maximumPoolSize = config.poolSize
            }

            StorageType.POSTGRESQL -> {
                hikari.jdbcUrl = "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
                hikari.driverClassName = "org.postgresql.Driver"
                hikari.username = config.username
                hikari.password = config.password
                hikari.maximumPoolSize = config.poolSize
            }

            StorageType.YAML -> error("YAML storage does not use a JDBC connection pool")
        }

        runCatching { Class.forName(hikari.driverClassName) }
            .onFailure { throw IllegalStateException("JDBC driver ${hikari.driverClassName} is unavailable", it) }
        return hikari
    }
}
