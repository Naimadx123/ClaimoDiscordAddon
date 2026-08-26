package zone.vao.claimoDiscordAddon.hook

import io.github.miniplaceholders.api.Expansion
import io.github.miniplaceholders.api.utils.Tags
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import org.bukkit.entity.Player
import zone.vao.claimoDiscordAddon.ClaimoDiscordAddon

@Suppress("UnstableApiUsage", "unused")
class DiscordMiniExpansion(private val plugin: ClaimoDiscordAddon) {

    fun register() {
        val builder = Expansion.builder("claimodiscord")
            .author(plugin.pluginMeta.authors.firstOrNull() ?: "Naimad (dc: 4g0)")
            .version(plugin.pluginMeta.version)
        for (name in DiscordPlaceholders.GLOBAL) {
            builder.globalPlaceholder(name) { _, _ -> tag(DiscordPlaceholders.global(plugin, name)) }
        }
        for (name in DiscordPlaceholders.SIMPLE) {
            builder.audiencePlaceholder(Player::class.java, name) { audience, _, _ ->
                tag(DiscordPlaceholders.simple(plugin, audience, name))
            }
        }
        for (prefix in DiscordPlaceholders.PREFIXES) {
            builder.audiencePlaceholder(Player::class.java, prefix) { audience, queue, _ ->
                if (!queue.hasNext()) return@audiencePlaceholder Tags.NULL_TAG
                tag(DiscordPlaceholders.value(plugin, audience, prefix, queue.pop().value()))
            }
        }
        builder.build().register()
    }

    private fun tag(value: String?): Tag =
        if (value == null) Tags.NULL_TAG else Tag.selfClosingInserting(Component.text(value))
}
