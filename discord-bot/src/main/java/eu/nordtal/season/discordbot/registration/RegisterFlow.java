package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

/**
 * Handles the team registration of one game.
 * It covers the Register button, the name modal, the partner picker and the direct message.
 * The game is the game of {@link Teams}. Every database call runs on {@code executor}.
 */
@Slf4j
public final class RegisterFlow extends ListenerAdapter {

    record Registered(@Example("Red_Fox") String name) {}

    record Invitation(TeamContext team) {}

    /** The modal that the Register button opens. It asks for the team name. */
    static final DocumentSpec<DocumentSpec.None> NAME = DocumentSpec.modal(
            StoredDocuments.NAMESPACE, "register-name", "Register", DocumentSpec.None.class, Slot.input("name"));

    /** The answer to a successful registration, with the button that invites a partner. */
    static final DocumentSpec<Registered> REGISTERED = DocumentSpec.message(
            StoredDocuments.NAMESPACE, "register-success", "Register", Registered.class, Slot.button("invite"));

    /** The question whom to invite, with a select of the guild's members. */
    static final DocumentSpec<DocumentSpec.None> PICKER =
            DocumentSpec.message(StoredDocuments.NAMESPACE, "invite-picker", "Register", Slot.select("partner"));

    /** The invitation that the partner receives, with an accept and a decline button. */
    static final DocumentSpec<Invitation> INVITATION = DocumentSpec.message(
            StoredDocuments.NAMESPACE,
            "invite-dm",
            "Register",
            Invitation.class,
            Slot.button("accept"),
            Slot.button("decline"));

    private final JDA jda;
    private final Teams teams;
    private final Ids ids;
    private final AccessReader access;
    private final DocumentRenderer documents;
    private final Executor executor;

    public RegisterFlow(
            final JDA jda,
            final Teams teams,
            final AccessReader access,
            final DocumentRenderer documents,
            final Executor executor) {
        this.jda = jda;
        this.teams = teams;
        this.ids = Ids.of(teams.game());
        this.access = access;
        this.documents = documents;
        this.executor = executor;
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (ids.register().equals(id)) {
            openRegisterModal(event);
        } else if (ids.invite().equals(id)) {
            openInvitePicker(event);
        } else if (id.startsWith(ids.inviteAccept())) {
            answerInvite(event, UUID.fromString(id.substring(ids.inviteAccept().length())), true);
        } else if (id.startsWith(ids.inviteDecline())) {
            answerInvite(event, UUID.fromString(id.substring(ids.inviteDecline().length())), false);
        }
    }

    private void openRegisterModal(final ButtonInteractionEvent event) {
        final Locale locale = access.language(DiscordId.of(event.getUser().getId()));

        final DocumentRenderer.FilledInput name = new DocumentRenderer.FilledInput(
                placeholder -> TextInput.create(ids.registerNameInput(), TextInputStyle.SHORT)
                        .setPlaceholder(placeholder)
                        .setRequiredRange(Teams.NAME_MIN_LENGTH, Teams.NAME_MAX_LENGTH)
                        .build());
        event.replyModal(documents.modal(
                        NAME, ids.registerModal(), new DocumentSpec.None(), Map.of("name", name), locale))
                .queue();
    }

    @Override
    public void onModalInteraction(final ModalInteractionEvent event) {
        if (!ids.registerModal().equals(event.getModalId())) {
            return;
        }
        final Locale locale = access.language(DiscordId.of(event.getUser().getId()));
        final String typed = event.getValue(ids.registerNameInput()) == null
                ? ""
                : event.getValue(ids.registerNameInput()).getAsString();

        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                register(event, locale, typed.strip());
            } catch (final RuntimeException exception) {
                log.error("Registering a {} team failed", teams.game().key(), exception);
                event.getHook()
                        .editOriginal(reply(locale, Sent.of(Replies.REGISTER_FAILED)))
                        .queue();
            }
        });
    }

    private void register(final ModalInteractionEvent event, final Locale locale, final String name) {
        final RegistrationResult result =
                teams.register(DiscordId.of(event.getUser().getId()), name);

        event.getHook()
                .editOriginal(
                        result.status() == RegistrationResult.Status.REGISTERED
                                ? documents
                                        .render(
                                                REGISTERED,
                                                new Registered(name),
                                                Map.of(
                                                        "invite",
                                                        new DocumentRenderer.FilledButton(
                                                                ids.invite(), ButtonStyle.SECONDARY)),
                                                locale)
                                        .edit()
                                : reply(locale, RegisterReplies.registration(result)))
                .queue();
    }

    private void openInvitePicker(final ButtonInteractionEvent event) {
        final Locale locale = access.language(DiscordId.of(event.getUser().getId()));
        event.reply(documents
                        .render(
                                PICKER,
                                new DocumentSpec.None(),
                                Map.of("partner", new DocumentRenderer.FilledMembers(ids.inviteSelect())),
                                locale)
                        .create())
                .setEphemeral(true)
                .queue();
    }

    @Override
    public void onEntitySelectInteraction(final EntitySelectInteractionEvent event) {
        if (!ids.inviteSelect().equals(event.getComponentId())) {
            return;
        }
        final Locale locale = access.language(DiscordId.of(event.getUser().getId()));
        final List<User> selected = event.getMentions().getUsers();
        if (selected.isEmpty()) {
            return;
        }
        final User partner = selected.getFirst();

        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                invite(event, locale, partner);
            } catch (final RuntimeException exception) {
                log.error("Inviting a {} partner failed", teams.game().key(), exception);
                event.getHook()
                        .editOriginal(reply(locale, Sent.of(Replies.REGISTER_FAILED)))
                        .queue();
            }
        });
    }

    private void invite(final EntitySelectInteractionEvent event, final Locale locale, final User partner) {
        if (partner.isBot()) {
            event.getHook()
                    .editOriginal(reply(locale, Sent.of(Replies.INVITE_UNAVAILABLE)))
                    .queue();
            return;
        }

        final InviteResult result = teams.invite(event.getUser().getId(), partner.getId());
        final DiscordMemberContext invited =
                new DiscordMemberContext(DiscordId.of(partner.getId()), partner.getEffectiveName());
        event.getHook()
                .editOriginal(reply(locale, RegisterReplies.invitation(result, invited)))
                .queue();
        if (result.status() == InviteResult.Status.INVITED) {
            dmInvite(partner, Objects.requireNonNull(result.memberId()), Objects.requireNonNull(result.teamName()));
        }
    }

    private void dmInvite(final User partner, final UUID memberId, final String teamName) {
        final Locale locale = access.language(DiscordId.of(partner.getId()));
        final DocumentRenderer.Drawn invitation = documents.render(
                INVITATION,
                new Invitation(new TeamContext(teamName)),
                Map.of(
                        "accept",
                        new DocumentRenderer.FilledButton(ids.inviteAccept() + memberId, ButtonStyle.SUCCESS),
                        "decline",
                        new DocumentRenderer.FilledButton(ids.inviteDecline() + memberId, ButtonStyle.DANGER)),
                locale);

        jda.openPrivateChannelById(partner.getId())
                .queue(
                        channel -> channel.sendMessage(invitation.create())
                                .queue(
                                        ok -> log.debug(
                                                "Sent a {} invite DM to {}",
                                                teams.game().key(),
                                                partner.getId()),
                                        failure -> log.info(
                                                "Could not DM {} about an invite ({}); they will "
                                                        + "only find out if the owner tells them",
                                                partner.getId(),
                                                failure.toString())),
                        failure -> log.info(
                                "Could not open a DM with {} for an invite ({})", partner.getId(), failure.toString()));
    }

    private MessageEditData reply(final Locale locale, final Sent<?> text) {
        return documents.reply(locale, text).edit();
    }

    private void answerInvite(final ButtonInteractionEvent event, final UUID memberId, final boolean accept) {
        final Locale locale = access.language(DiscordId.of(event.getUser().getId()));
        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                final AnswerResult result = accept
                        ? teams.accept(memberId, event.getUser().getId())
                        : teams.decline(memberId, event.getUser().getId());
                report(event, locale, accept, result);
            } catch (final RuntimeException exception) {
                log.error("Answering a {} invite failed", teams.game().key(), exception);
                event.getHook()
                        .editOriginal(reply(locale, Sent.of(Replies.REGISTER_FAILED)))
                        .queue();
            }
        });
    }

    private void report(
            final ButtonInteractionEvent event, final Locale locale, final boolean accept, final AnswerResult result) {
        final DocumentRenderer.Drawn reply = documents.reply(locale, RegisterReplies.answer(result, accept));
        if (result.status() == AnswerResult.Status.CLOSED) {
            // The buttons stay, because the invite can be answered when the round opens.
            event.getHook().sendMessage(reply.create()).setEphemeral(true).queue();
            return;
        }
        event.getHook().editOriginal(reply.edit()).queue();
        if (result.status() != AnswerResult.Status.ANSWERED) {
            return;
        }

        final String teamName = Objects.requireNonNull(result.teamName());
        final UUID teamId = Objects.requireNonNull(result.teamId());
        teams.ownerOf(teamId).ifPresent(ownerId -> {
            final Locale ownerLocale = access.language(DiscordId.of(ownerId));
            final DiscordMemberContext player = new DiscordMemberContext(
                    DiscordId.of(event.getUser().getId()), event.getUser().getEffectiveName());
            final DocumentRenderer.Drawn note =
                    documents.reply(ownerLocale, RegisterReplies.ownerNote(accept, player, new TeamContext(teamName)));
            jda.openPrivateChannelById(ownerId)
                    .queue(
                            channel -> channel.sendMessage(note.create())
                                    .queue(
                                            ok -> {},
                                            failure -> log.info(
                                                    "Could not DM team owner {} about an invite answer ({})",
                                                    ownerId,
                                                    failure.toString())),
                            failure -> log.info(
                                    "Could not open a DM with team owner {} ({})", ownerId, failure.toString()));
        });
    }
}
