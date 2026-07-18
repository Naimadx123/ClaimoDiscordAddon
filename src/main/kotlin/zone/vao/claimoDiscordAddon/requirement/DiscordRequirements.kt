package zone.vao.claimoDiscordAddon.requirement

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import zone.vao.claimo.ClaimoApi
import zone.vao.claimo.requirement.Requirement
import zone.vao.claimo.requirement.RequirementContext
import zone.vao.claimo.requirement.RequirementInput
import zone.vao.claimo.requirement.RequirementResult
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon
import zone.vao.claimoDiscordAddon.config.Messages
import zone.vao.claimoDiscordAddon.discord.DiscordManager
import zone.vao.claimoDiscordAddon.storage.LinkStorage
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

object DiscordRequirements {

    fun register(plugin: ClaimoDiscordAddon) {
        val storage = { plugin.linkStorage }
        val messages = { plugin.configuration.messages }
        val discord = { plugin.discord }
        val executor = plugin.discordExecutor

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.LINKED,
            { LinkedRequirement(storage(), messages(), executor) },
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.MEMBER,
            { MemberRequirement(storage(), messages(), discord(), executor) },
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.BOOSTER,
            { BoosterRequirement(storage(), messages(), discord(), executor) },
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.ROLE,
            { cfg ->
                RoleRequirement(
                    storage(), messages(), discord(), executor,
                    cfg.getStrings("roles") + cfg.getStrings("role"),
                    cfg.getStrings("denied-roles") + cfg.getStrings("denied-role"),
                )
            },
            listOf(
                RequirementInput.TextInput("roles", "Required roles (id or name, comma-separated)"),
                RequirementInput.TextInput("denied-roles", "Forbidden roles (id or name, comma-separated)"),
            ),
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.MESSAGES,
            { cfg -> MessagesRequirement(storage(), messages(), executor, cfg.getInt("amount", 0).toLong()) },
            listOf(RequirementInput.NumberInput("amount", "Discord messages", min = 0.0, max = 100_000.0, step = 5.0)),
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.COMMAND,
            { cfg ->
                CommandRequirement(
                    storage(), messages(), executor,
                    cfg.getString("command", "").orEmpty().trim(),
                    cfg.getInt("amount", 1).toLong(),
                )
            },
            listOf(
                RequirementInput.TextInput("command", "Discord slash command name"),
                RequirementInput.NumberInput("amount", "Times to use it", min = 1.0, max = 10_000.0, step = 1.0, initial = 1.0),
                RequirementInput.TextInput("description", "Command description (Discord)"),
            ),
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.SERVER_TAG,
            { cfg -> ServerTagRequirement(storage(), messages(), discord(), executor, cfg.getString("tag")?.trim()?.ifBlank { null }) },
            listOf(RequirementInput.TextInput("tag", "Server tag text (optional)")),
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.STATUS,
            { cfg ->
                StatusRequirement(
                    storage(), messages(), discord(), executor,
                    cfg.getString("value", "").orEmpty().trim(),
                    cfg.getString("match", "contains").orEmpty().trim().lowercase(),
                )
            },
            listOf(
                RequirementInput.TextInput("value", "Status text to require"),
                RequirementInput.TextInput("match", "Match mode (contains or equals)", initial = "contains"),
            ),
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.ACCOUNT_AGE,
            { cfg ->
                val duration = cfg.getString("duration")?.trim().orEmpty()
                val requiredMillis =
                    if (duration.isNotEmpty()) parseDurationMillis(duration) else cfg.getLong("days", 0L) * DAY_MILLIS
                AccountAgeRequirement(storage(), messages(), executor, requiredMillis)
            },
            listOf(RequirementInput.TextInput("duration", "Account age (e.g. 30d, 2w)", initial = "30d")),
        )

        ClaimoApi.registerRequirement(
            DiscordRequirementTypes.MEMBER_SINCE,
            { cfg ->
                val duration = cfg.getString("duration")?.trim().orEmpty()
                val requiredMillis =
                    if (duration.isNotEmpty()) parseDurationMillis(duration) else cfg.getLong("days", 0L) * DAY_MILLIS
                MemberSinceRequirement(storage(), messages(), discord(), executor, requiredMillis)
            },
            listOf(RequirementInput.TextInput("duration", "Time on the server (e.g. 30d, 2w)", initial = "30d")),
        )
    }

    fun unregister() {
        DiscordRequirementTypes.TYPES.forEach { ClaimoApi.unregisterRequirement(it) }
    }

    fun commandSpecs(): List<DiscordManager.CustomCommandSpec> =
        ClaimoApi.vouchers()
            .flatMap { it.requirements }
            .filter { it.type.equals(DiscordRequirementTypes.COMMAND, ignoreCase = true) }
            .mapNotNull { cfg ->
                val name = cfg.getString("command", "").orEmpty().trim().lowercase()
                if (name.isEmpty()) return@mapNotNull null
                DiscordManager.CustomCommandSpec(
                    name,
                    cfg.getString("description", "Claimo reward command").orEmpty().ifBlank { "Claimo reward command" },
                    cfg.getInt("amount", 1).toLong(),
                )
            }
            .distinctBy { it.name }
}

private fun satisfiedIf(condition: Boolean, description: Component): RequirementResult =
    if (condition) RequirementResult.satisfied(description) else RequirementResult.unsatisfied(description)

private fun discordId(uuid: UUID, storage: LinkStorage, executor: Executor): CompletableFuture<Long?> =
    CompletableFuture.supplyAsync({ storage.loadProfile(uuid).discordId }, executor).exceptionally { null }

private class LinkedRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val executor: Executor,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenApply { id ->
            satisfiedIf(id != null, messages.line("requirement-linked"))
        }
}

private class MemberRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val discord: DiscordManager,
    private val executor: Executor,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenCompose { id ->
            when {
                id == null -> completed(messages.line("not-linked"))
                !discord.isReady() -> completed(messages.line("bot-offline"))
                else -> discord.retrieveMember(id).thenApply { member ->
                    satisfiedIf(member != null, messages.line("requirement-member"))
                }
            }
        }
}

private class BoosterRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val discord: DiscordManager,
    private val executor: Executor,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenCompose { id ->
            when {
                id == null -> completed(messages.line("not-linked"))
                !discord.isReady() -> completed(messages.line("bot-offline"))
                else -> discord.retrieveMember(id).thenApply { member ->
                    satisfiedIf(member != null && member.timeBoosted != null, messages.line("requirement-booster"))
                }
            }
        }
}

private class RoleRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val discord: DiscordManager,
    private val executor: Executor,
    private val required: List<String>,
    private val denied: List<String>,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenCompose { id ->
            when {
                id == null -> completed(messages.line("not-linked"))
                !discord.isReady() -> completed(messages.line("bot-offline"))
                else -> discord.retrieveMember(id).thenApply { member ->
                    val description = messages.line("requirement-role")
                    if (member == null) return@thenApply RequirementResult.unsatisfied(description)
                    val names = member.roles.map { it.name.lowercase() }
                    val ids = member.roles.map { it.id }
                    fun holds(token: String): Boolean {
                        val value = token.trim()
                        return value.isNotEmpty() && (ids.contains(value) || names.contains(value.lowercase()))
                    }
                    val hasRequired = required.isEmpty() || required.any(::holds)
                    val hasDenied = denied.any(::holds)
                    satisfiedIf(hasRequired && !hasDenied, description)
                }
            }
        }
}

private class MessagesRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val executor: Executor,
    private val amount: Long,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenApply { id ->
            if (id == null) return@thenApply RequirementResult.unsatisfied(messages.line("not-linked"))
            val sent = storage.messages(id)
            satisfiedIf(
                sent >= amount,
                messages.line(
                    "requirement-messages",
                    Placeholder.parsed("sent", sent.toString()),
                    Placeholder.parsed("amount", amount.toString()),
                ),
            )
        }
}

private class CommandRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val executor: Executor,
    private val command: String,
    private val amount: Long,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenApply { id ->
            if (id == null) return@thenApply RequirementResult.unsatisfied(messages.line("not-linked"))
            val used = storage.commandUses(command, id)
            satisfiedIf(
                command.isNotEmpty() && used >= amount,
                messages.line(
                    "requirement-command",
                    Placeholder.unparsed("command", command),
                    Placeholder.parsed("used", used.toString()),
                    Placeholder.parsed("amount", amount.toString()),
                ),
            )
        }
}

private class ServerTagRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val discord: DiscordManager,
    private val executor: Executor,
    private val expectedTag: String?,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenCompose { id ->
            if (id == null) return@thenCompose completed(messages.line("not-linked"))
            discord.fetchPrimaryGuild(id).thenApply { primary ->
                val tagText = primary?.tag ?: expectedTag.orEmpty()
                val description = messages.line("requirement-server-tag", Placeholder.unparsed("tag", tagText))
                val matches = primary?.guildId == discord.guildId &&
                    (expectedTag == null || primary.tag.equals(expectedTag, ignoreCase = true))
                satisfiedIf(matches, description)
            }
        }
}

private class StatusRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val discord: DiscordManager,
    private val executor: Executor,
    private val value: String,
    private val match: String,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenApply { id ->
            val description = messages.line("requirement-status", Placeholder.unparsed("value", value))
            when {
                id == null -> RequirementResult.unsatisfied(messages.line("not-linked"))
                !discord.isReady() -> RequirementResult.unsatisfied(messages.line("bot-offline"))
                else -> {
                    val status = discord.customStatus(id).orEmpty()
                    val matches = when (match) {
                        "equals", "equal", "==" -> status.equals(value, ignoreCase = true)
                        else -> status.contains(value, ignoreCase = true)
                    }
                    satisfiedIf(value.isNotEmpty() && matches, description)
                }
            }
        }
}

private class AccountAgeRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val executor: Executor,
    private val requiredMillis: Long,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenApply { id ->
            if (id == null) return@thenApply RequirementResult.unsatisfied(messages.line("not-linked"))
            val created = (id shr 22) + DISCORD_EPOCH
            val age = (System.currentTimeMillis() - created).coerceAtLeast(0L)
            satisfiedIf(
                age >= requiredMillis,
                messages.line(
                    "requirement-account-age",
                    Placeholder.parsed("current", humanizeDuration(age)),
                    Placeholder.parsed("required", humanizeDuration(requiredMillis)),
                ),
            )
        }
}

private class MemberSinceRequirement(
    private val storage: LinkStorage,
    private val messages: Messages,
    private val discord: DiscordManager,
    private val executor: Executor,
    private val requiredMillis: Long,
) : Requirement {
    override fun check(context: RequirementContext): CompletableFuture<RequirementResult> =
        discordId(context.player.uniqueId, storage, executor).thenCompose { id ->
            when {
                id == null -> completed(messages.line("not-linked"))
                !discord.isReady() -> completed(messages.line("bot-offline"))
                else -> discord.retrieveMember(id).thenApply { member ->
                    if (member == null) return@thenApply RequirementResult.unsatisfied(messages.line("requirement-member"))
                    val joined = if (member.hasTimeJoined()) member.timeJoined.toInstant().toEpochMilli() else 0L
                    val duration = (System.currentTimeMillis() - joined).coerceAtLeast(0L)
                    satisfiedIf(
                        duration >= requiredMillis,
                        messages.line(
                            "requirement-member-since",
                            Placeholder.parsed("current", humanizeDuration(duration)),
                            Placeholder.parsed("required", humanizeDuration(requiredMillis)),
                        ),
                    )
                }
            }
        }
}

private const val DISCORD_EPOCH = 1_420_070_400_000L
private const val DAY_MILLIS = 86_400_000L
private val DURATION_REGEX = Regex("(\\d+)\\s*([smhdw])", RegexOption.IGNORE_CASE)

private fun parseDurationMillis(text: String): Long {
    var total = 0L
    for (match in DURATION_REGEX.findAll(text)) {
        val value = match.groupValues[1].toLongOrNull() ?: continue
        total += when (match.groupValues[2].lowercase()) {
            "s" -> value * 1000L
            "m" -> value * 60_000L
            "h" -> value * 3_600_000L
            "d" -> value * DAY_MILLIS
            "w" -> value * 7L * DAY_MILLIS
            else -> 0L
        }
    }
    if (total == 0L) text.trim().toLongOrNull()?.let { return it * DAY_MILLIS }
    return total
}

private fun humanizeDuration(millis: Long): String {
    val days = millis / DAY_MILLIS
    val hours = (millis % DAY_MILLIS) / 3_600_000L
    return when {
        days > 0L && hours > 0L -> "${days}d ${hours}h"
        days > 0L -> "${days}d"
        else -> "${hours}h"
    }
}

private fun completed(description: Component): CompletableFuture<RequirementResult> =
    CompletableFuture.completedFuture(RequirementResult.unsatisfied(description))
