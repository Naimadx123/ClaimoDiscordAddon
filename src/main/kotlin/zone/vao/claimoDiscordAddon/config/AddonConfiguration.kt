package zone.vao.claimoDiscordAddon.config

import zone.vao.claimoDiscordAddon.storage.StorageConfig

data class AddonConfiguration(
    val token: String,
    val guildId: Long,
    val activity: String,
    val presenceIntent: Boolean,
    val membersIntent: Boolean,
    val codeLength: Int,
    val codeExpirySeconds: Long,
    val linkCommandName: String,
    val linkCommandDescription: String,
    val linkOptionName: String,
    val linkOptionDescription: String,
    val discordLinkSuccess: String,
    val discordLinkInvalid: String,
    val discordLinkTaken: String,
    val discordCommandReply: String,
    val storage: StorageConfig,
    val messages: Messages,
) {
    fun hasToken(): Boolean = token.isNotBlank() && token != "PASTE-YOUR-BOT-TOKEN-HERE"
}
