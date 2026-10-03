package eu.nordtal.s2.discordbot.access.discord;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.LinkRedemption;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.discordbot.Ids;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.modals.Modal;

/**
 * Account linking on the Discord side: the link message's modal and {@code /unlink}.
 *
 * {@link RedemptionLimit} counts guesses per Discord account; every unlink is written to the admin channel.
 */
@Slf4j
public final class LinkFlow extends ListenerAdapter {

    private static final int CODE_MIN_LENGTH = 4;
    private static final int CODE_MAX_LENGTH = 16;

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final DiscordRenderer messages;
    private final AdminLog admin;
    private final RedemptionLimit limit;
    private final ExecutorService executor;

    public LinkFlow(
            final AccessDirectory access,
            final AccessRoles roles,
            final DiscordRenderer messages,
            final AdminLog admin,
            final RedemptionLimit limit,
            final ExecutorService executor) {
        this.access = access;
        this.roles = roles;
        this.messages = messages;
        this.admin = admin;
        this.limit = limit;
        this.executor = executor;
    }

    /** Returns the commands the bot registers on startup, open to everyone. */
    public static List<CommandData> commands() {
        return List.of(Commands.slash("unlink", "Remove the Minecraft account linked to your Discord account."));
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        if (!Ids.LINK.equals(event.getComponentId())) {
            return;
        }
        final Locale locale = roles.localeOf(DiscordId.of(event.getUser().getId()));

        final TextInput codeInput = TextInput.create(Ids.LINK_CODE_INPUT, TextInputStyle.SHORT)
                .setPlaceholder(messages.format(locale, MESSAGES.link().modal().codePlaceholder()))
                .setRequiredRange(CODE_MIN_LENGTH, CODE_MAX_LENGTH)
                .build();
        final Modal modal = Modal.create(
                        Ids.LINK_MODAL,
                        messages.format(locale, MESSAGES.link().modal().title()))
                .addComponents(
                        Label.of(messages.format(locale, MESSAGES.link().modal().codeLabel()), codeInput))
                .build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(final ModalInteractionEvent event) {
        if (!Ids.LINK_MODAL.equals(event.getModalId())) {
            return;
        }
        final Locale locale = roles.localeOf(DiscordId.of(event.getUser().getId()));
        final String typed = event.getValue(Ids.LINK_CODE_INPUT) == null
                ? ""
                : event.getValue(Ids.LINK_CODE_INPUT).getAsString();
        // Codes are generated upper-case; normalising means a lower-case type-in is not punished.
        final String code = typed.strip().toUpperCase(Locale.ROOT);

        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                redeem(event, locale, code);
            } catch (final RuntimeException exception) {
                log.error("Redeeming a link code failed", exception);
                admin.alert(new Alert(
                        AlertType.BOT,
                        Alert.Level.DOWN,
                        "link",
                        "A link failed",
                        event.getUser().getAsMention() + " `" + exception + "`",
                        "/access"));
                event.getHook()
                        .editOriginal(messages.format(locale, MESSAGES.link().failed()))
                        .queue();
            }
        });
    }

    private void redeem(final ModalInteractionEvent event, final Locale locale, final String code) {
        final DiscordId discordId = DiscordId.of(event.getUser().getId());

        // Taken before the database is touched, and atomically: two workers cannot share the last attempt.
        final int remaining = limit.acquire(discordId);
        if (remaining < 0) {
            event.getHook()
                    .editOriginal(messages.format(locale, MESSAGES.link().tooMany()))
                    .queue();
            return;
        }

        // Only a wrong guess keeps the attempt: a redemption failing on an unreachable database proves no guess.
        boolean wrongGuess = false;
        try {
            final LinkRedemption result = access.redeemLinkCode(discordId, code);

            switch (result.status()) {
                case LINKED -> {
                    // LINKED guarantees mcUuid, per LinkRedemption's contract.
                    final UUID linked = Objects.requireNonNull(result.mcUuid());
                    limit.clear(discordId);
                    admin.record(new AuditLine(
                            JournalAction.LINK,
                            Actor.person(discordId),
                            discordId,
                            linked,
                            TEXTS.journal().link()));
                    event.getHook()
                            .editOriginal(
                                    messages.format(locale, MESSAGES.link().success()))
                            .queue();
                }
                case INVALID_CODE -> {
                    wrongGuess = true;
                    if (remaining == 0) {
                        log.warn("{} has used up its link-code attempts for this hour", discordId);
                        // One person mistyping a code looks like this too.
                        admin.note(
                                "🔒 Too many wrong codes",
                                event.getUser().getAsMention() + " is refused for the rest of the hour.");
                    }
                    event.getHook()
                            .editOriginal(
                                    messages.format(locale, MESSAGES.link().invalidCode()))
                            .queue();
                }
                // Not counted: the code was real and the account already has one.
                case ALREADY_LINKED ->
                    event.getHook()
                            .editOriginal(
                                    messages.format(locale, MESSAGES.link().alreadyLinked()))
                            .queue();
            }
        } finally {
            if (!wrongGuess) {
                limit.release(discordId);
            }
        }
    }

    @Override
    public void onSlashCommandInteraction(final SlashCommandInteractionEvent event) {
        if (!"unlink".equals(event.getFullCommandName())) {
            return;
        }
        final DiscordId discordId = DiscordId.of(event.getUser().getId());
        final Locale locale = roles.localeOf(discordId);

        event.deferReply(true).queue();
        executor.execute(() -> {
            final Optional<UUID> mcUuid = access.linkedMinecraftAccount(discordId);
            if (!access.unlink(discordId)) {
                event.getHook()
                        .editOriginal(messages.format(locale, MESSAGES.unlink().none()))
                        .queue();
                return;
            }

            admin.record(new AuditLine(
                    JournalAction.UNLINK,
                    Actor.person(discordId),
                    discordId,
                    mcUuid.orElse(null),
                    TEXTS.journal().unlink(true)));
            event.getHook()
                    .editOriginal(messages.format(locale, MESSAGES.unlink().success()))
                    .queue();
        });
    }
}
