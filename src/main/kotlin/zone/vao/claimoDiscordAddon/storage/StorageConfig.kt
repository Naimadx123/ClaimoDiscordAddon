package zone.vao.claimoDiscordAddon.storage

enum class StorageType { YAML, SQLITE, MYSQL, MARIADB, POSTGRESQL }

data class StorageConfig(
    val type: StorageType,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val tablePrefix: String,
    val poolSize: Int,
)
