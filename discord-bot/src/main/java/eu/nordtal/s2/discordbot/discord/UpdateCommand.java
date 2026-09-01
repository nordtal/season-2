package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code /update} - what is new, install it, restart the network.
 *
 * <h2>The bot does not update anything</h2>
 * It cannot: the updater is a different container with the volumes mounted, and this one has
 * neither the jars nor the schema. What this class does is <b>write a row into
 * {@code update_request} and read the answer back</b> (docs/updater.md#how-it-is-operated). Every
 * word an admin sees here is the updater's own report, rendered once, by the process that did the
 * work - not a second rendering that could disagree with the first.
 *
 * <h2>Three clicks, in the order that makes them safe</h2>
 * The command reports and changes nothing. <b>Install</b> migrates and swaps jars, and still
 * restarts nothing - the servers keep running the code they loaded at startup until somebody says
 * otherwise. <b>Restart</b> starts a countdown that every player on the network sees, and can be
 * stopped for as long as it runs. That ordering is the whole design: the report is read before
 * anything moves, and the move is finished before anything goes down.
 *
 * <h2>Waiting without holding a thread</h2>
 * An install downloads a Paper jar and seven plugins; it takes minutes, not seconds. So nothing
 * here blocks: the answer is waited for by re-reading one indexed row on the bot's existing timer,
 * and the message is edited when it arrives. Discord's own limit is what bounds the wait - an
 * interaction token is good for fifteen minutes, and this gives up before that so the last thing
 * the admin sees is a sentence and not a message that stopped changing.
 */
@Slf4j
public final class UpdateCommand extends ListenerAdapter {

    /** How often the answer row is re-read. One indexed lookup; a person is watching. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(2);

    /**
     * How long to wait for the updater before saying so.
     * <p>
     * Short of Discord's fifteen-minute interaction token, on purpose: the message has to be
     * editable when the wait gives up, or the admin is left looking at a spinner that will never
     * resolve and no explanation anywhere.
     * </p>
     */
    private static final Duration PATIENCE = Duration.ofMinutes(12);

    /** Discord's embed description limit, minus the code fence this puts around the report. */
    private static final int DESCRIPTION_BUDGET = 4000;

    private static final String NOT_AN_ADMIN =
            "You are not an admin. Updates are run by whoever holds the admin role in this guild, "
                    + "and nothing else.";

    private final UpdateDirectory updates;
    private final AdminLog admin;
    private final AdminFlagDao dao;
    private final ExecutorService worker;
    private final ScheduledExecutorService timers;

    public UpdateCommand(final UpdateDirectory updates, final AdminLog admin, final Jdbi jdbi,
                         final ExecutorService worker, final ScheduledExecutorService timers) {
        this.updates = updates;
        this.admin = admin;
        this.dao = jdbi.onDemand(AdminFlagDao.class);
        this.worker = worker;
        this.timers = timers;
    }

    /** What the bot registers with Discord on startup. */
    public static List<CommandData> commands() {
        return List.of(Commands.slash("update",
                        "What is newer than what the network is running, and installing it.")
                // Only hides it. The authorisation is discord_user.admin, checked on every click.
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED));
    }

    // ---------------------------------------------------------------- the command

    @Override
    public void onSlashCommandInteraction(final @NotNull SlashCommandInteractionEvent event) {
        if (!"update".equals(event.getName())) {
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> submit(event.getHook(), event.getUser().getId(), UpdateKind.REPORT));
    }

    // ---------------------------------------------------------------- the buttons

    @Override
    public void onButtonInteraction(final @NotNull ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (!Ids.UPDATE_INSTALL.equals(id) && !Ids.UPDATE_RESTART.equals(id)
                && !Ids.UPDATE_CANCEL.equals(id)) {
            // Every other flow's buttons come through here too.
            return;
        }

        event.deferEdit().queue();
        worker.execute(() -> {
            if (Ids.UPDATE_CANCEL.equals(id)) {
                cancel(event.getHook(), event.getUser());
                return;
            }
            submit(event.getHook(), event.getUser().getId(),
                    Ids.UPDATE_INSTALL.equals(id) ? UpdateKind.APPLY : UpdateKind.RESTART);
        });
    }

    // ---------------------------------------------------------------- writing the row

    private void submit(final InteractionHook hook, final String userId, final UpdateKind kind) {
        try {
            // Checked on every click and not only on the command: a confirmation can sit on screen
            // while the role is taken away, and these are the clicks that change something.
            if (!dao.isAdmin(userId).orElse(false)) {
                hook.editOriginal(NOT_AN_ADMIN).setEmbeds(List.of()).setComponents(List.of()).queue();
                return;
            }

            final Duration delay = kind == UpdateKind.RESTART
                    ? UpdateDirectory.RESTART_COUNTDOWN : Duration.ZERO;
            final UpdateRequest request =
                    updates.submit(kind, UpdateSource.DISCORD, userId, delay);

            if (kind == UpdateKind.RESTART) {
                announceCountdown(hook, userId, request);
            } else {
                hook.editOriginal(waiting(kind)).setEmbeds(List.of()).setComponents(List.of()).queue();
            }
            watch(hook, request, Instant.now().plus(PATIENCE));
        } catch (final RuntimeException failure) {
            fail(hook, "writing the " + kind + " request", failure);
        }
    }

    /**
     * The restart is the one request whose answer is not the interesting part.
     * <p>
     * It is claimed a minute after it is written, and the thing that claims it takes this bot down
     * with everything else. So the message says what is about to happen and offers the way out,
     * rather than promising a result it will not be alive to deliver.
     * </p>
     */
    private void announceCountdown(final InteractionHook hook, final String userId,
                                   final UpdateRequest request) {
        admin.note("<@" + userId + "> asked for a restart of the whole network. Everybody online "
                + "is being counted down and it happens in "
                + UpdateDirectory.RESTART_COUNTDOWN.toSeconds() + " seconds.");

        hook.editOriginal(new MessageEditBuilder()
                        .setContent("Restarting the whole network in **"
                                + UpdateDirectory.RESTART_COUNTDOWN.toSeconds() + " seconds**."
                                + " Everyone online is being counted down, wherever they are."
                                + "\nThis message stops updating when the bot goes down with it -"
                                + " that is the restart working.")
                        .setEmbeds(List.of())
                        .setComponents(ActionRow.of(
                                Button.secondary(Ids.UPDATE_CANCEL, "Stop the countdown")))
                        .build())
                .queue();
    }

    private void cancel(final InteractionHook hook, final net.dv8tion.jda.api.entities.User user) {
        try {
            if (!dao.isAdmin(user.getId()).orElse(false)) {
                hook.editOriginal(NOT_AN_ADMIN).setEmbeds(List.of()).setComponents(List.of()).queue();
                return;
            }
            final Optional<UpdateRequest> cancelled = updates.cancelPendingRestart(
                    "Cancelled in Discord by " + user.getName());

            if (cancelled.isPresent()) {
                admin.note(user.getAsMention() + " stopped the restart before it happened.");
                hook.editOriginal("Stopped. Nothing is restarting.")
                        .setEmbeds(List.of()).setComponents(List.of()).queue();
            } else {
                hook.editOriginal("Too late - the restart has already begun. Nothing was changed.")
                        .setEmbeds(List.of()).setComponents(List.of()).queue();
            }
        } catch (final RuntimeException failure) {
            fail(hook, "cancelling the restart", failure);
        }
    }

    // ---------------------------------------------------------------- reading the answer back

    /**
     * Re-reads the row until it reaches a terminal state, then edits the message.
     * <p>
     * On the shared timer rather than a thread of its own: the check is one indexed lookup, and the
     * bot has three other timers on the same executor. A rescheduled task rather than a loop, so
     * nothing is held while an install downloads sixty megabytes.
     * </p>
     */
    private void watch(final InteractionHook hook, final UpdateRequest request, final Instant deadline) {
        timers.schedule(() -> {
            try {
                final Optional<UpdateRequest> row = updates.find(request.id());
                if (row.isEmpty()) {
                    hook.editOriginal("That request is gone from the database. Nothing happened.")
                            .setEmbeds(List.of()).setComponents(List.of()).queue();
                    return;
                }
                final UpdateRequest current = row.get();
                if (current.status().isFinished()) {
                    hook.editOriginal(finished(current)).queue();
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    hook.editOriginal(timedOut(current))
                            .setEmbeds(List.of()).setComponents(List.of()).queue();
                    return;
                }
                watch(hook, request, deadline);
            } catch (final RuntimeException failure) {
                fail(hook, "reading the answer to request " + request.id(), failure);
            }
        }, CHECK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- what an admin sees

    private static String waiting(final UpdateKind kind) {
        return kind == UpdateKind.APPLY
                ? "Installing. This downloads a Paper jar and every plugin, so it takes a few "
                        + "minutes; nothing restarts when it is done."
                : "Asking the updater what is new...";
    }

    /**
     * The finished request, as an embed with the updater's own report in it.
     *
     * <p>The buttons offered afterwards are the honest next step and nothing more: a report leads
     * to an install, an install leads to a restart, and a failure leads to neither - there is no
     * button on a run that went wrong, because the next thing to do is read what it says.</p>
     */
    private static MessageEditData finished(final UpdateRequest request) {
        final boolean failed = request.status() != UpdateStatus.DONE;
        final MessageEditBuilder message = new MessageEditBuilder()
                .setContent("")
                .setEmbeds(embed(request, failed));

        if (failed || request.kind() == UpdateKind.RESTART) {
            return message.setComponents(List.of()).build();
        }
        return message.setComponents(ActionRow.of(button(request.kind()))).build();
    }

    private static Button button(final UpdateKind kind) {
        return kind == UpdateKind.REPORT
                ? Button.primary(Ids.UPDATE_INSTALL, "Install")
                : Button.danger(Ids.UPDATE_RESTART, "Restart the network");
    }

    private static List<MessageEmbed> embed(final UpdateRequest request, final boolean failed) {
        final String report = request.result() == null ? "(the updater wrote nothing)" : request.result();
        final String body = report.length() > DESCRIPTION_BUDGET
                ? report.substring(0, DESCRIPTION_BUDGET)
                        + "\n... truncated; the updater's log has all of it"
                : report;

        return List.of(new net.dv8tion.jda.api.EmbedBuilder()
                .setTitle(title(request))
                .setDescription("```\n" + body + "\n```")
                // Red for a failure, grey otherwise. Deliberately not green for "an update is
                // available": that is neither good news nor bad news, it is just news.
                .setColor(failed ? new Color(0xC0, 0x39, 0x2B) : new Color(0x99, 0xAA, 0xB5))
                .setTimestamp(request.finished())
                .build());
    }

    private static String title(final UpdateRequest request) {
        return switch (request.kind()) {
            case REPORT -> "What is new";
            case APPLY -> "Installed";
            case RESTART -> "Restart";
        };
    }

    private static String timedOut(final UpdateRequest request) {
        return "The updater has not answered in " + PATIENCE.toMinutes() + " minutes. The request "
                + "is still row " + request.id() + " in `update_request` and it is "
                + request.status() + " - if it is still PENDING, the `updater` container is not "
                + "running. Nothing here was changed either way.";
    }

    /**
     * One place for "that did not work".
     * <p>
     * The admin gets a plain sentence, the admin channel gets the detail. This is the surface that
     * moves jars on four servers; a failure nobody sees is the one thing it must not produce.
     * </p>
     */
    private void fail(final InteractionHook hook, final String what, final RuntimeException failure) {
        log.error("An update interaction failed while {}", what, failure);
        admin.alert("An update interaction failed while " + what + ": `" + failure + "`");
        hook.editOriginal("That did not work. The admin channel has the detail.")
                .setEmbeds(List.of())
                .setComponents(List.of())
                .queue();
    }
}
