package eu.nordtal.s2.accessbot.discord;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.LinkRedemption;
import eu.nordtal.s2.common.message.Messages;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.modals.Modal;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Stage C's half of account linking: the managed link message's button opens a modal for the
 * code, and {@code /unlink} lets a user remove their own link. See docs/access-stage-c.md.
 *
 * <h2>The code does the validating</h2>
 * Nothing here parses or checks the code beyond trimming and upper-casing it -
 * {@link AccessDirectory#redeemLinkCode(String, String)} owns expiry and the 1:1, the same way the
 * database owns the constraints behind it. This class only turns a
 * {@link LinkRedemption.Status} into the right message.
 *
 * <h2>Unlink has no waiting period, and is always reported</h2>
 * {@code /unlink} only ever touches the caller's own link - there is no target user parameter,
 * because the concept ("the user may unlink themselves") is self-service, not an admin action.
 * A link removed this way is written to the admin channel every time, unconditionally: without a
 * waiting period, that log is the only thing that makes a shared access visible.
 */
@Slf4j
public final class LinkFlow extends ListenerAdapter {

    private static final int CODE_MIN_LENGTH = 4;
    private static final int CODE_MAX_LENGTH = 16;

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final Messages messages;
    private final AdminLog admin;
    private final ExecutorService executor;

    public LinkFlow(final AccessDirectory access, final AccessRoles roles, final Messages messages,
                    final AdminLog admin, final ExecutorService executor) {
        this.access = access;
        this.roles = roles;
        this.messages = messages;
        this.admin = admin;
        this.executor = executor;
    }

    /** What the bot registers with Discord on startup - available to everyone, unlike the admin commands. */
    public static List<CommandData> commands() {
        return List.of(Commands.slash("unlink",
                "Remove the Minecraft account linked to your Discord account."));
    }

    @Override
    public void onButtonInteraction(final @NotNull ButtonInteractionEvent event) {
        if (!Ids.LINK.equals(event.getComponentId())) {
            return;
        }
        final Locale locale = roles.localeOf(event.getUser().getId());

        final TextInput codeInput = TextInput.create(Ids.LINK_CODE_INPUT, TextInputStyle.SHORT)
                .setPlaceholder(messages.get(locale, "link.modal.code-placeholder"))
                .setRequiredRange(CODE_MIN_LENGTH, CODE_MAX_LENGTH)
                .build();
        final Modal modal = Modal.create(Ids.LINK_MODAL, messages.get(locale, "link.modal.title"))
                .addComponents(Label.of(messages.get(locale, "link.modal.code-label"), codeInput))
                .build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(final @NotNull ModalInteractionEvent event) {
        if (!Ids.LINK_MODAL.equals(event.getModalId())) {
            return;
        }
        final Locale locale = roles.localeOf(event.getUser().getId());
        final String typed = event.getValue(Ids.LINK_CODE_INPUT) == null
                ? "" : event.getValue(Ids.LINK_CODE_INPUT).getAsString();
        // LinkCodes in :common generates upper-case codes; normalising here means a player who
        // reads the code off a disconnect screen and types it in lower case is not punished for it.
        final String code = typed.strip().toUpperCase(Locale.ROOT);

        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                redeem(event, locale, code);
            } catch (final RuntimeException exception) {
                log.error("Redeeming a link code failed", exception);
                admin.alert("Redeeming a link code failed: `" + exception + "`");
                event.getHook().editOriginal(messages.get(locale, "link.failed")).queue();
            }
        });
    }

    private void redeem(final ModalInteractionEvent event, final Locale locale, final String code) {
        final LinkRedemption result = access.redeemLinkCode(event.getUser().getId(), code);

        switch (result.status()) {
            case LINKED -> {
                admin.record("LINK", null, event.getUser().getId(), result.mcUuid(), "redeemed a link code");
                admin.note(event.getUser().getAsMention() + " linked Minecraft account `"
                        + result.mcUuid() + "`.");
                event.getHook().editOriginal(messages.get(locale, "link.success")).queue();
            }
            case INVALID_CODE ->
                    event.getHook().editOriginal(messages.get(locale, "link.invalid-code")).queue();
            case ALREADY_LINKED ->
                    event.getHook().editOriginal(messages.get(locale, "link.already-linked")).queue();
        }
    }

    // ---------------------------------------------------------------- /unlink

    @Override
    public void onSlashCommandInteraction(final @NotNull SlashCommandInteractionEvent event) {
        if (!"unlink".equals(event.getFullCommandName())) {
            return;
        }
        final String discordId = event.getUser().getId();
        final Locale locale = roles.localeOf(discordId);

        event.deferReply(true).queue();
        executor.execute(() -> {
            final Optional<UUID> mcUuid = access.linkedMinecraftAccount(discordId);
            if (!access.unlink(discordId)) {
                event.getHook().editOriginal(messages.get(locale, "unlink.none")).queue();
                return;
            }

            admin.record("UNLINK", discordId, discordId, mcUuid.orElse(null), "self-service, no waiting period");
            admin.note(event.getUser().getAsMention() + " unlinked Minecraft account `"
                    + mcUuid.map(UUID::toString).orElse("?") + "`.");
            event.getHook().editOriginal(messages.get(locale, "unlink.success")).queue();
        });
    }
}
