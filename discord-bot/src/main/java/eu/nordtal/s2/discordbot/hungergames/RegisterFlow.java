package eu.nordtal.s2.discordbot.hungergames;

import eu.nordtal.s2.common.message.Messages;

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
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Team registration end to end: the {@link Ids#REGISTER} button opens the team name modal, the
 * confirmation offers {@link Ids#INVITE} which opens a user picker, and the invited partner gets a
 * DM with accept/decline. See {@code docs/hunger-games.md#registration}.
 * <p>
 * <b>Readiness is not here.</b> {@code hg_member.ready} is written only by the {@code hunger-games}
 * Paper plugin's lobby broadcast - nothing in this class reads or writes it (decided explicitly,
 * since the concept places "I am ready" entirely in the in-game lobby).
 * </p>
 * <p>
 * Blocking work - every database call - runs on {@code executor}, off the gateway thread, the same
 * discipline {@code LinkFlow}/{@code PurchaseFlow} use.
 * </p>
 */
@Slf4j
public final class RegisterFlow extends ListenerAdapter {

    private static final int NAME_MIN_LENGTH = 3;
    private static final int NAME_MAX_LENGTH = 15;

    private final JDA jda;
    private final Teams teams;
    private final Messages messages;
    private final ExecutorService executor;

    public RegisterFlow(final JDA jda, final Teams teams, final Messages messages, final ExecutorService executor) {
        this.jda = jda;
        this.teams = teams;
        this.messages = messages;
        this.executor = executor;
    }

    // ---------------------------------------------------------------- register

    @Override
    public void onButtonInteraction(final @NotNull ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (Ids.REGISTER.equals(id)) {
            openRegisterModal(event);
        } else if (Ids.INVITE.equals(id)) {
            openInvitePicker(event);
        } else if (id.startsWith(Ids.INVITE_ACCEPT)) {
            answerInvite(event, UUID.fromString(id.substring(Ids.INVITE_ACCEPT.length())), true);
        } else if (id.startsWith(Ids.INVITE_DECLINE)) {
            answerInvite(event, UUID.fromString(id.substring(Ids.INVITE_DECLINE.length())), false);
        }
    }

    private void openRegisterModal(final ButtonInteractionEvent event) {
        final Locale locale = teams.localeOf(event.getUser().getId());

        final TextInput nameInput = TextInput.create(Ids.REGISTER_NAME_INPUT, TextInputStyle.SHORT)
                .setPlaceholder(messages.get(locale, "register.modal.name-placeholder"))
                .setRequiredRange(NAME_MIN_LENGTH, NAME_MAX_LENGTH)
                .build();
        final Modal modal = Modal.create(Ids.REGISTER_MODAL, messages.get(locale, "register.modal.title"))
                .addComponents(Label.of(messages.get(locale, "register.modal.name-label"), nameInput))
                .build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(final @NotNull ModalInteractionEvent event) {
        if (!Ids.REGISTER_MODAL.equals(event.getModalId())) {
            return;
        }
        final Locale locale = teams.localeOf(event.getUser().getId());
        final String typed = event.getValue(Ids.REGISTER_NAME_INPUT) == null
                ? "" : event.getValue(Ids.REGISTER_NAME_INPUT).getAsString();

        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                register(event, locale, typed.strip());
            } catch (final RuntimeException exception) {
                log.error("Registering a hunger games team failed", exception);
                event.getHook().editOriginal(messages.get(locale, "register.failed")).queue();
            }
        });
    }

    private void register(final ModalInteractionEvent event, final Locale locale, final String name) {
        final RegistrationResult result = teams.register(event.getUser().getId(), name);

        switch (result.status()) {
            case REGISTERED -> event.getHook().editOriginalComponents(List.of())
                    .setContent(messages.format(locale, "register.success", "name", name))
                    .setComponents(ActionRow.of(
                            Button.secondary(Ids.INVITE, messages.get(locale, "register.invite-button"))))
                    .queue();
            case INVALID_NAME ->
                    event.getHook().editOriginal(messages.get(locale, "register.invalid-name")).queue();
            case NAME_TAKEN ->
                    event.getHook().editOriginal(messages.get(locale, "register.name-taken")).queue();
            case ALREADY_REGISTERED ->
                    event.getHook().editOriginal(messages.get(locale, "register.already-registered")).queue();
        }
    }

    // ---------------------------------------------------------------- invite

    private void openInvitePicker(final ButtonInteractionEvent event) {
        final Locale locale = teams.localeOf(event.getUser().getId());
        final EntitySelectMenu picker = EntitySelectMenu.create(Ids.INVITE_SELECT, EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder(messages.get(locale, "register.invite.picker-placeholder"))
                .build();
        event.reply(messages.get(locale, "register.invite.pick"))
                .setEphemeral(true)
                .addComponents(ActionRow.of(picker))
                .queue();
    }

    @Override
    public void onEntitySelectInteraction(final @NotNull EntitySelectInteractionEvent event) {
        if (!Ids.INVITE_SELECT.equals(event.getComponentId())) {
            return;
        }
        final Locale locale = teams.localeOf(event.getUser().getId());
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
                log.error("Inviting a hunger games partner failed", exception);
                event.getHook().editOriginal(messages.get(locale, "register.failed")).queue();
            }
        });
    }

    private void invite(final EntitySelectInteractionEvent event, final Locale locale, final User partner) {
        if (partner.isBot()) {
            event.getHook().editOriginal(messages.get(locale, "register.invite.target-unavailable")).queue();
            return;
        }

        final InviteResult result = teams.invite(event.getUser().getId(), partner.getId());
        switch (result.status()) {
            case INVITED -> {
                event.getHook().editOriginal(messages.format(locale, "register.invite.sent",
                        "partner", partner.getAsMention())).queue();
                dmInvite(partner, result.memberId(), result.teamName());
            }
            case NOT_REGISTERED, NOT_OWNER ->
                    event.getHook().editOriginal(messages.get(locale, "register.invite.not-owner")).queue();
            case TEAM_FULL ->
                    event.getHook().editOriginal(messages.get(locale, "register.invite.team-full")).queue();
            case INVITE_PENDING ->
                    event.getHook().editOriginal(messages.get(locale, "register.invite.pending")).queue();
            case CANNOT_INVITE_SELF ->
                    event.getHook().editOriginal(messages.get(locale, "register.invite.cannot-invite-self")).queue();
            case TARGET_UNAVAILABLE ->
                    event.getHook().editOriginal(messages.get(locale, "register.invite.target-unavailable")).queue();
        }
    }

    private void dmInvite(final User partner, final UUID memberId, final String teamName) {
        final Locale locale = teams.localeOf(partner.getId());
        final String text = messages.format(locale, "register.invite.dm", "team", teamName);
        final List<ActionRow> components = List.of(ActionRow.of(
                Button.success(Ids.INVITE_ACCEPT + memberId, messages.get(locale, "register.invite.accept")),
                Button.danger(Ids.INVITE_DECLINE + memberId, messages.get(locale, "register.invite.decline"))));

        jda.openPrivateChannelById(partner.getId()).queue(
                channel -> channel.sendMessage(text).addComponents(components).queue(
                        ok -> log.debug("Sent hunger games invite DM to {}", partner.getId()),
                        failure -> log.info("Could not DM {} about a hunger games invite ({}); they will "
                                + "only find out if the owner tells them", partner.getId(), failure.toString())),
                failure -> log.info("Could not open a DM with {} for a hunger games invite ({})",
                        partner.getId(), failure.toString()));
    }

    // ---------------------------------------------------------------- accept / decline

    private void answerInvite(final ButtonInteractionEvent event, final UUID memberId, final boolean accept) {
        final Locale locale = teams.localeOf(event.getUser().getId());
        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                final AnswerResult result = accept
                        ? teams.accept(memberId, event.getUser().getId())
                        : teams.decline(memberId, event.getUser().getId());
                report(event, locale, accept, result);
            } catch (final RuntimeException exception) {
                log.error("Answering a hunger games invite failed", exception);
                event.getHook().editOriginalComponents(List.of())
                        .setContent(messages.get(locale, "register.failed")).queue();
            }
        });
    }

    private void report(final ButtonInteractionEvent event, final Locale locale, final boolean accept,
                        final AnswerResult result) {
        if (result.status() == AnswerResult.Status.NOT_PENDING) {
            event.getHook().editOriginalComponents(List.of())
                    .setContent(messages.get(locale, "register.invite.no-longer-pending")).queue();
            return;
        }

        final String key = accept ? "register.invite.accepted" : "register.invite.declined";
        event.getHook().editOriginalComponents(List.of())
                .setContent(messages.format(locale, key, "team", result.teamName())).queue();

        teams.ownerOf(result.teamId()).ifPresent(ownerId -> {
            final Locale ownerLocale = teams.localeOf(ownerId);
            final String ownerKey = accept ? "register.invite.owner-notified-accepted"
                    : "register.invite.owner-notified-declined";
            final String text = messages.format(ownerLocale, ownerKey,
                    "team", result.teamName(), "player", event.getUser().getAsMention());
            jda.openPrivateChannelById(ownerId).queue(
                    channel -> channel.sendMessage(text).queue(ok -> { }, failure ->
                            log.info("Could not DM team owner {} about an invite answer ({})",
                                    ownerId, failure.toString())),
                    failure -> log.info("Could not open a DM with team owner {} ({})",
                            ownerId, failure.toString()));
        });
    }
}
