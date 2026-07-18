package zone.vao.claimoDiscordAddon.config

import zone.vao.claimoDiscordAddon.storage.StorageConfig

data class AddonConfiguration(
    val commandName: String,
    val token: String,
    val guildId: Long,
    val activity: String,
    val presenceIntent: Boolean,
    val membersIntent: Boolean,
    val codeLength: Int,
    val codeExpirySeconds: Long,
    val discordLinkSuccess: String,
    val discordLinkInvalid: String,
    val discordLinkTaken: String,
    val discordLinkAlready: String,
    val discordCommandReply: String,
    val linkButtonLabel: String,
    val linkModalTitle: String,
    val linkModalFieldLabel: String,
    val linkModalFieldPlaceholder: String,
    val panelChannelId: Long,
    val panelEmbedTitle: String,
    val panelEmbedDescription: String,
    val panelEmbedColor: Int,
    val linkEmbedTitle: String,
    val linkEmbedColor: Int,
    val linkEmbedFooter: String,
    val storage: StorageConfig,
    val messages: Messages,
) {
    fun hasToken(): Boolean = token.isNotBlank() && token != "PASTE-YOUR-BOT-TOKEN-HERE"
}
