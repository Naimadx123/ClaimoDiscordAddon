package zone.vao.claimoDiscordAddon.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import zone.vao.claimoDiscordAddon.discord.DiscordProfile
import java.util.UUID

class SqlLinkStorage(hikariConfig: HikariConfig, tablePrefix: String) : LinkStorage {

    private val linksTable = "${tablePrefix}links"
    private val statsTable = "${tablePrefix}stats"
    private val dataSource = HikariDataSource(hikariConfig)

    private val lock = Any()
    private val messages = HashMap<Long, Long>()
    private val commands = HashMap<String, HashMap<Long, Long>>()
    private var dirty = false

    init {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $linksTable " +
                        "(uuid VARCHAR(36) PRIMARY KEY, discord_id BIGINT NOT NULL)"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $statsTable " +
                        "(discord_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, name VARCHAR(190) NOT NULL, " +
                        "count BIGINT NOT NULL, PRIMARY KEY (discord_id, kind, name))"
                )
            }
        }
        loadStats()
    }

    override fun loadProfile(uuid: UUID): DiscordProfile {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT discord_id FROM $linksTable WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return DiscordProfile(uuid)
                    return DiscordProfile(uuid, rs.getLong(1))
                }
            }
        }
    }

    override fun saveProfile(profile: DiscordProfile) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $linksTable WHERE uuid = ?").use { ps ->
                ps.setString(1, profile.uuid.toString())
                ps.executeUpdate()
            }
            val discordId = profile.discordId ?: return
            conn.prepareStatement("INSERT INTO $linksTable (uuid, discord_id) VALUES (?, ?)").use { ps ->
                ps.setString(1, profile.uuid.toString())
                ps.setLong(2, discordId)
                ps.executeUpdate()
            }
        }
    }

    override fun findByDiscordId(discordId: Long): DiscordProfile? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT uuid FROM $linksTable WHERE discord_id = ?").use { ps ->
                ps.setLong(1, discordId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val uuid = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: return null
                    return DiscordProfile(uuid, discordId)
                }
            }
        }
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
        if (!dirty) return
        dataSource.connection.use { conn ->
            messages.forEach { (discordId, count) -> upsert(conn, discordId, LinkStorage.KIND_MESSAGE, "", count) }
            commands.forEach { (command, users) ->
                users.forEach { (discordId, count) -> upsert(conn, discordId, LinkStorage.KIND_COMMAND, command, count) }
            }
        }
        dirty = false
    }

    override fun close() {
        flush()
        dataSource.close()
    }

    private fun loadStats() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT discord_id, kind, name, count FROM $statsTable").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val discordId = rs.getLong(1)
                        val count = rs.getLong(4)
                        when (rs.getString(2)) {
                            LinkStorage.KIND_MESSAGE -> messages[discordId] = count
                            LinkStorage.KIND_COMMAND ->
                                commands.getOrPut(rs.getString(3).lowercase()) { HashMap() }[discordId] = count
                        }
                    }
                }
            }
        }
    }

    private fun upsert(conn: java.sql.Connection, discordId: Long, kind: String, name: String, count: Long) {
        conn.prepareStatement(
            "UPDATE $statsTable SET count = ? WHERE discord_id = ? AND kind = ? AND name = ?"
        ).use { ps ->
            ps.setLong(1, count)
            ps.setLong(2, discordId)
            ps.setString(3, kind)
            ps.setString(4, name)
            if (ps.executeUpdate() > 0) return
        }
        conn.prepareStatement(
            "INSERT INTO $statsTable (discord_id, kind, name, count) VALUES (?, ?, ?, ?)"
        ).use { ps ->
            ps.setLong(1, discordId)
            ps.setString(2, kind)
            ps.setString(3, name)
            ps.setLong(4, count)
            ps.executeUpdate()
        }
    }
}
