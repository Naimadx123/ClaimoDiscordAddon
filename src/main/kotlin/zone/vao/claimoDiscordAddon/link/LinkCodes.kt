package zone.vao.claimoDiscordAddon.link

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LinkCodes(private val codeLength: Int, private val expirySeconds: Long) {

    private data class Pending(val uuid: UUID, val expiresAt: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val random = SecureRandom()

    fun create(uuid: UUID): String {
        pending.values.removeIf { it.uuid == uuid }
        val code = (1..codeLength).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        pending[code] = Pending(uuid, System.currentTimeMillis() + expirySeconds * 1000L)
        return code
    }

    fun redeem(code: String): UUID? {
        purgeExpired()
        val entry = pending.remove(code.trim().uppercase()) ?: return null
        return if (entry.expiresAt < System.currentTimeMillis()) null else entry.uuid
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        pending.values.removeIf { it.expiresAt < now }
    }

    private companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
