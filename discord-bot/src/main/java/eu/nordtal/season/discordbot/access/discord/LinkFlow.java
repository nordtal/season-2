package eu.nordtal.season.discordbot.access.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.LinkRedemption;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Mention;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

@Slf4j
public final class LinkFlow extends ListenerAdapter {

    private static final int CODE_MIN_LENGTH = 4;
    private static final int CODE_MAX_LENGTH = 16;

    static final DocumentSpec<DocumentSpec.None> CODE = DocumentSpec.modal(
            StoredDocuments.NAMESPACE, "link-code", "Link", DocumentSpec.None.class, Slot.input("code"));

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final DocumentRenderer documents;
    private final AdminLog admin;
    private final RedemptionLimit limit;
    private final Executor executor;

    public LinkFlow(
            final AccessDirectory access,
            final AccessRoles roles,
            final DocumentRenderer documents,
            final AdminLog admin,
            final RedemptionLimit limit,
            final Executor executor) {
        this.access = access;
        this.roles = roles;
        this.documents = documents;
        this.admin = admin;
        this.limit = limit;
        this.executor = executor;
    }

    public static List<CommandData> commands() {
        return List.of(Commands.slash("unlink", "Remove the Minecraft account linked to your Discord account."));
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        if (!Ids.LINK.equals(event.getComponentId())) {
            return;
        }
        final Locale locale = roles.localeOf(DiscordId.of(event.getUser().getId()));

        final DocumentRenderer.FilledInput code = new DocumentRenderer.FilledInput(
                placeholder -> TextInput.create(Ids.LINK_CODE_INPUT, TextInputStyle.SHORT)
                        .setPlaceholder(placeholder)
                        .setRequiredRange(CODE_MIN_LENGTH, CODE_MAX_LENGTH)
                        .build());
        event.replyModal(documents.modal(CODE, Ids.LINK_MODAL, new DocumentSpec.None(), Map.of("code", code), locale))
                .queue();
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
                        TEXTS.alert().linkFailed(),
                        List.of(TEXTS.alert().failedFor(member(event.getUser()), String.valueOf(exception))),
                        "/access"));
                event.getHook().editOriginal(reply(locale, Replies.LINK_FAILED)).queue();
            }
        });
    }

    private void redeem(final ModalInteractionEvent event, final Locale locale, final String code) {
        final DiscordId discordId = DiscordId.of(event.getUser().getId());

        // Acquire before the database is touched. The acquire is atomic, so two workers cannot share the last attempt.
        final int remaining = limit.acquire(discordId);
        if (remaining < 0) {
            event.getHook().editOriginal(reply(locale, Replies.LINK_TOO_MANY)).queue();
            return;
        }

        // Only a wrong guess keeps the attempt. A redemption that fails on an unreachable database proves no guess.
        boolean wrongGuess = false;
        try {
            final LinkRedemption result = access.redeemLinkCode(discordId, code);

            switch (result.status()) {
                case LINKED -> {
                    final UUID linked = Objects.requireNonNull(result.mcUuid());
                    limit.clear(discordId);
                    admin.record(new AuditLine(JournalAction.LINK, Actor.person(discordId), discordId, linked, null));
                    event.getHook()
                            .editOriginal(reply(locale, Replies.LINK_SUCCESS))
                            .queue();
                }
                case INVALID_CODE -> {
                    wrongGuess = true;
                    if (remaining == 0) {
                        lockedOut(event.getUser());
                    }
                    event.getHook()
                            .editOriginal(reply(locale, Replies.LINK_INVALID_CODE))
                            .queue();
                }
                case ALREADY_LINKED ->
                    event.getHook()
                            .editOriginal(reply(locale, Replies.LINK_ALREADY_LINKED))
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
                event.getHook().editOriginal(reply(locale, Replies.UNLINK_NONE)).queue();
                return;
            }

            admin.record(new AuditLine(
                    JournalAction.UNLINK,
                    Actor.person(discordId),
                    discordId,
                    mcUuid.orElse(null),
                    new JournalDetail.Unlinked(true)));
            event.getHook().editOriginal(reply(locale, Replies.UNLINK_SUCCESS)).queue();
        });
    }

    private MessageEditData reply(final Locale locale, final DocumentSpec<DocumentSpec.None> text) {
        return documents.reply(locale, text).edit();
    }

    static Mention member(final User user) {
        return new Mention(DiscordId.of(user.getId()), user.getName());
    }

    private void lockedOut(final User user) {
        log.warn("{} has used up its link-code attempts for this hour", user.getId());
        admin.record(lockedOut(DiscordId.of(user.getId())));
    }

    public static AuditLine lockedOut(final DiscordId member) {
        return AuditLine.about(JournalAction.LINK_CODES_EXHAUSTED, Actor.BOT, member, null);
    }
}
