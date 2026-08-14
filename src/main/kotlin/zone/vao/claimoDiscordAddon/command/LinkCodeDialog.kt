package zone.vao.claimoDiscordAddon.command

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.claimoDiscordAddon.config.Messages

@Suppress("UnstableApiUsage")
object LinkCodeDialog {

    fun show(player: Player, messages: Messages, code: String, expiry: String) {
        val resolvers = arrayOf(
            Placeholder.unparsed("code", code),
            Placeholder.unparsed("expiry", expiry),
        )
        val copyButton = ActionButton.create(
            messages.line("dialog-link-copy", *resolvers),
            null,
            150,
            DialogAction.staticAction(ClickEvent.copyToClipboard(code)),
        )
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.line("dialog-link-title", *resolvers))
                        .body(listOf(DialogBody.plainMessage(messages.line("dialog-link-body", *resolvers))))
                        .build(),
                )
                .type(DialogType.notice(copyButton))
        }
        player.showDialog(dialog)
    }
}
