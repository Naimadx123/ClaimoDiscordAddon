package zone.vao.claimoDiscordAddon.requirement

object DiscordRequirementTypes {

    const val LINKED = "discord_linked"
    const val MEMBER = "discord_member"
    const val ROLE = "discord_role"
    const val BOOSTER = "discord_booster"
    const val MESSAGES = "discord_messages"
    const val COMMAND = "discord_command"
    const val SERVER_TAG = "discord_server_tag"
    const val STATUS = "discord_status"

    val TYPES: List<String> = listOf(LINKED, MEMBER, ROLE, BOOSTER, MESSAGES, COMMAND, SERVER_TAG, STATUS)
}
