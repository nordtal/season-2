package eu.nordtal.season.discordbot.registration;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

/**
 * Team registration for one game end to end: the Register button, the name modal, the partner picker and the DM.
 *
 * The game is the one its {@link Teams} register for. Every database call runs on {@code executor}.
 */
@Slf4j
public final class RegisterFlow extends ListenerAdapter {

    private final JDA jda;
    private final Teams teams;
    private final Ids ids;
    private final AccessReader access;
    private final DiscordRenderer messages;
    private final Executor executor;

    public RegisterFlow(
            final JDA jda,
            final Teams teams,
            final AccessReader access,
            final DiscordRenderer messages,
            final Executor executor) {
        this.jda = jda;
        this.teams = teams;
        this.ids = Ids.of(teams.game());
        this.access = access;
        this.messages = messages;
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

        final TextInput nameInput = TextInput.create(ids.registerNameInput(), TextInputStyle.SHORT)
                .setPlaceholder(
                        messages.format(locale, MESSAGES.register().modal().namePlaceholder()))
                .setRequiredRange(Teams.NAME_MIN_LENGTH, Teams.NAME_MAX_LENGTH)
                .build();
        final Modal modal = Modal.create(
                        ids.registerModal(),
                        messages.format(locale, MESSAGES.register().modal().title()))
                .addComponents(Label.of(
                        messages.format(locale, MESSAGES.register().modal().nameLabel()), nameInput))
                .build();

        event.replyModal(modal).queue();
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
                        .editOriginal(
                                messages.format(locale, MESSAGES.register().failed()))
                        .queue();
            }
        });
    }

    private void register(final ModalInteractionEvent event, final Locale locale, final String name) {
        final RegistrationResult result =
                teams.register(DiscordId.of(event.getUser().getId()), name);

        final MessageRef reply = RegisterReplies.registration(result, name);
        if (result.status() == RegistrationResult.Status.REGISTERED) {
            event.getHook()
                    .editOriginalComponents(List.of())
                    .setContent(messages.format(locale, reply))
                    .setComponents(ActionRow.of(Button.secondary(
                            ids.invite(),
                            messages.format(locale, MESSAGES.register().inviteButton()))))
                    .queue();
        } else {
            event.getHook().editOriginal(messages.format(locale, reply)).queue();
        }
    }

    private void openInvitePicker(final ButtonInteractionEvent event) {
        final Locale locale = access.language(DiscordId.of(event.getUser().getId()));
        final EntitySelectMenu picker = EntitySelectMenu.create(ids.inviteSelect(), EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder(
                        messages.format(locale, MESSAGES.register().invite().pickerPlaceholder()))
                .build();
        event.reply(messages.format(locale, MESSAGES.register().invite().pick()))
                .setEphemeral(true)
                .addComponents(ActionRow.of(picker))
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
                        .editOriginal(
                                messages.format(locale, MESSAGES.register().failed()))
                        .queue();
            }
        });
    }

    private void invite(final EntitySelectInteractionEvent event, final Locale locale, final User partner) {
        if (partner.isBot()) {
            event.getHook()
                    .editOriginal(
                            messages.format(locale, MESSAGES.register().invite().targetUnavailable()))
                    .queue();
            return;
        }

        final InviteResult result = teams.invite(event.getUser().getId(), partner.getId());
        final DiscordMemberContext invited =
                new DiscordMemberContext(DiscordId.of(partner.getId()), partner.getEffectiveName());
        event.getHook()
                .editOriginal(messages.format(locale, RegisterReplies.invitation(result, invited)))
                .queue();
        if (result.status() == InviteResult.Status.INVITED) {
            // INVITED guarantees these two.
            dmInvite(partner, Objects.requireNonNull(result.memberId()), Objects.requireNonNull(result.teamName()));
        }
    }

    private void dmInvite(final User partner, final UUID memberId, final String teamName) {
        final Locale locale = access.language(DiscordId.of(partner.getId()));
        final String text = messages.format(locale, MESSAGES.register().invite().dm(new TeamContext(teamName)));
        final List<ActionRow> components = List.of(ActionRow.of(
                Button.success(
                        ids.inviteAccept() + memberId,
                        messages.format(locale, MESSAGES.register().invite().accept())),
                Button.danger(
                        ids.inviteDecline() + memberId,
                        messages.format(locale, MESSAGES.register().invite().decline()))));

        jda.openPrivateChannelById(partner.getId())
                .queue(
                        channel -> channel.sendMessage(text)
                                .addComponents(components)
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
                        .editOriginalComponents(List.of())
                        .setContent(messages.format(locale, MESSAGES.register().failed()))
                        .queue();
            }
        });
    }

    private void report(
            final ButtonInteractionEvent event, final Locale locale, final boolean accept, final AnswerResult result) {
        final String reply = messages.format(locale, RegisterReplies.answer(result, accept));
        if (result.status() == AnswerResult.Status.CLOSED) {
            // The buttons stay: the invite can still be answered once the round opens again.
            event.getHook().sendMessage(reply).setEphemeral(true).queue();
            return;
        }
        event.getHook().editOriginalComponents(List.of()).setContent(reply).queue();
        if (result.status() != AnswerResult.Status.ANSWERED) {
            return;
        }

        // ANSWERED guarantees both.
        final String teamName = Objects.requireNonNull(result.teamName());
        final UUID teamId = Objects.requireNonNull(result.teamId());
        teams.ownerOf(teamId).ifPresent(ownerId -> {
            final Locale ownerLocale = access.language(DiscordId.of(ownerId));
            final DiscordMemberContext player = new DiscordMemberContext(
                    DiscordId.of(event.getUser().getId()), event.getUser().getEffectiveName());
            final String text =
                    messages.format(ownerLocale, RegisterReplies.ownerNote(accept, player, new TeamContext(teamName)));
            jda.openPrivateChannelById(ownerId)
                    .queue(
                            channel -> channel.sendMessage(text)
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
