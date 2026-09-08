package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
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
 * <h2>Two clicks: look, then confirm</h2>
 * {@code /update} reports and changes nothing. <b>Update now</b> is the confirmation, and behind it
 * is one run: a countdown every player on the network sees, then the servers whose jars change are
 * stopped, the schema and the jars are moved with nothing running on them, and each server is
 * started again and watched until it reports healthy.
 *
 * <p>It used to be three clicks, the middle one being <em>install</em> - which swapped jars into
 * running servers and is finding 147. There is no button for that any more, because the button was
 * the defect.</p>

 * <h2>The report is drawn, not quoted</h2>
 * Until 2026-09-07 the updater's whole report went into the embed inside a code fence, which is
 * unreadable at a glance and says nothing while a run is working. The updater now answers with an
 * {@code UpdateReport}, and this class draws it as one field per service. It still decides
 * nothing - every version, every comparison and every outcome in those fields is the updater's.
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

    /**
     * <b>No {@code commands()} here any more.</b>
     *
     * <p>{@code /update} was declared in this class and registered from {@code AccessBot} until
     * 2026-09-08. It is now one declaration in {@code :commands} that all three adapters render, so
     * who may run it, in which language, and what is said back are decided once. What is left in
     * this class is the half that is genuinely Discord's: the buttons, and the embed that follows
     * a run and is edited in place as it works.</p>
     */
    /**
     * Follow a request that a folded command has just written, and draw it.
     *
     * <p>The entry point from {@code :commands}: {@code ReportUpdate} and {@code RunUpdate} write
     * the row and then ask this process to show it. Everything below - the polling, the embed, the
     * fields per service, the buttons that follow - is this surface's business and no command's.</p>
     *
     * @param user the asker, which on this surface always carries the interaction to edit
     * @param id   the request to follow
     */
    public void follow(final eu.nordtal.s2.commands.NordtalUser user, final long id) {
        if (!(user instanceof DiscordUser discord)) {
            // A NordtalUser that is not a Discord one cannot reach an interaction, and there is
            // nothing to draw on. Reachable only if this effects object is ever handed to another
            // adapter, which would be a wiring mistake rather than a runtime condition.
            log.warn("An update was asked for through the Discord effects by a {}, which carries no"
                    + " interaction to draw on. Request {} still ran.", user.getClass(), id);
            return;
        }
        updates.find(id).ifPresent(request ->
                watch(discord.hook(), request, Instant.now().plus(PATIENCE)));
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
                    Ids.UPDATE_INSTALL.equals(id) ? UpdateKind.UPDATE : UpdateKind.RESTART);
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

            // Both kinds that take servers down get the countdown; a report takes nothing down
            // and waiting thirty seconds to be told what is new would be theatre.
            final Duration delay = kind.stopsServers()
                    ? UpdateDirectory.UPDATE_COUNTDOWN : Duration.ZERO;
            final UpdateRequest request =
                    updates.submit(kind, UpdateSource.DISCORD, userId, delay);

            if (kind.stopsServers()) {
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
     * What an admin sees for the thirty seconds before anything moves.
     *
     * <h2>The wording, and why it changed</h2>
     * This used to read <em>"Everybody online is being counted down and it happens in 60
     * seconds"</em>, which says a thing to a person rather than about one, and promised a restart
     * of "the whole network" that the run no longer performs - only the servers whose jars actually
     * change are stopped. It also ended with a sentence about this message dying with the bot,
     * which was true when a redeploy took the whole project down and is not any more: the bot is
     * not stopped unless its own jar changes, so the embed below keeps updating through the run.
     */
    private void announceCountdown(final InteractionHook hook, final String userId,
                                   final UpdateRequest request) {
        final long seconds = UpdateDirectory.UPDATE_COUNTDOWN.toSeconds();
        final String what = request.kind() == UpdateKind.RESTART ? "a restart" : "an update";

        admin.note("<@" + userId + "> started " + what + ". Every player online sees a "
                + seconds + "-second countdown, and the servers involved are stopped, "
                + (request.kind() == UpdateKind.RESTART ? "" : "updated ") + "and started again"
                + " after it.");

        hook.editOriginal(new MessageEditBuilder()
                        .setContent("Starting in **" + seconds + " seconds**. Every player online"
                                + " sees the countdown."
                                + "\nNothing has been installed yet - this can still be stopped.")
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
                // Named from the row, not from the button: since 2026-09-08 the same countdown and
                // the same cancel serve an UPDATE as well as a RESTART, and saying "the restart"
                // for an update that was about to replace jars is the wrong thing in the admin log
                // - which is the record somebody reads weeks later to work out what happened.
                final String what = cancelled.get().kind() == UpdateKind.UPDATE
                        ? "update" : "restart";
                admin.note(user.getAsMention() + " stopped the " + what + " before it happened.");
                hook.editOriginal("Stopped. Nothing was changed.")
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
    private void watch(final InteractionHook hook, final UpdateRequest request,
                       final Instant deadline) {
        watch(hook, request, deadline, null);
    }

    /**
     * @param drawn the report text this message is currently showing, so that the embed is only
     *              edited when it has something new to say. Discord rate-limits message edits, and
     *              a run writes a stage at a time while this polls every two seconds - re-sending
     *              an identical embed twenty times between two stages would spend that budget on
     *              nothing
     */
    private void watch(final InteractionHook hook, final UpdateRequest request,
                       final Instant deadline, final String drawn) {
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

                // The run rewrites its own report as it goes - stopping, installing, starting,
                // waiting for healthchecks. Redrawing here is what turns five minutes of silence
                // into something a person can watch; before 2026-09-07 the embed appeared only at
                // the end, and there was nothing to see because the run was three seconds long.
                String showing = drawn;
                final String progress = current.result();
                if (progress != null && !progress.equals(drawn)) {
                    UpdateReports.parse(progress).ifPresent(report -> hook
                            .editOriginal(new MessageEditBuilder()
                                    .setContent("")
                                    .setEmbeds(List.of(fields(report, current)))
                                    .setComponents(List.of())
                                    .build())
                            .queue());
                    showing = progress;
                }
                watch(hook, request, deadline, showing);
            } catch (final RuntimeException failure) {
                fail(hook, "reading the answer to request " + request.id(), failure);
            }
        }, CHECK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- what an admin sees

    private static String waiting(final UpdateKind kind) {
        return kind == UpdateKind.UPDATE
                ? "Updating. The servers involved are stopped, their jars replaced and started "
                        + "again, and each one is watched until it reports healthy."
                : "Asking the updater what is new...";
    }

    /**
     * The finished request, as an embed with the updater's own report in it.
     *
     * <p>The button offered afterwards is the honest next step and nothing more: a report that
     * found work leads to the run that does it, and nothing else offers a button - a finished
     * update has nothing to follow it, and a failure leads nowhere at all, because the next thing
     * to do is read what it says.</p>
     */
    private static MessageEditData finished(final UpdateRequest request) {
        // A cancelled restart is not a failure - it is somebody using the way out on purpose, and
        // the watch below overwrites the "Stopped." line with this embed either way. Colouring it
        // red would turn a deliberate act into something that looks like it went wrong.
        final boolean failed = request.status() == UpdateStatus.FAILED;
        final MessageEditBuilder message = new MessageEditBuilder()
                .setContent("")
                .setEmbeds(embed(request, failed));

        if (request.status() != UpdateStatus.DONE || request.kind() != UpdateKind.REPORT) {
            return message.setComponents(List.of()).build();
        }
        // A report that found nothing gets no button either: "Update now" under a list of things
        // that are all current is an invitation to take four servers down for nothing.
        final boolean worth = UpdateReports.parse(request.result())
                .map(UpdateReport::isWork)
                .orElse(true);
        return worth
                ? message.setComponents(ActionRow.of(button(request.kind()))).build()
                : message.setComponents(List.of()).build();
    }

    private static Button button(final UpdateKind kind) {
        return kind == UpdateKind.REPORT
                ? Button.danger(Ids.UPDATE_INSTALL, "Update now")
                : Button.danger(Ids.UPDATE_RESTART, "Restart the network");
    }

    /**
     * The updater's report, drawn as an embed.
     *
     * <h2>Fields, not a code fence</h2>
     * Until 2026-09-07 the whole report went into the description inside {@code ```}, which reads
     * as a wall of monospace and cannot show anything while a run works. The report is now
     * structured, so each service gets its own inline field: its state, and the artefacts moving
     * under it. The description carries only what belongs to no service - the pack, the migration,
     * a reason nothing happened.
     *
     * <p><b>A row written before this change is still plain text</b>, and there are such rows in
     * the deployed database. {@link UpdateReports#parse} answers empty for those and the old
     * rendering is used, unchanged. Nothing is migrated: a finished request is never read twice.</p>
     */
    private static List<MessageEmbed> embed(final UpdateRequest request, final boolean failed) {
        final String result = request.result();
        final Optional<UpdateReport> report = UpdateReports.parse(result);
        if (report.isEmpty()) {
            return List.of(new net.dv8tion.jda.api.EmbedBuilder()
                    .setTitle(title(request))
                    .setDescription("```\n" + truncate(result == null
                            ? "(the updater wrote nothing)" : result) + "\n```")
                    .setColor(colour(failed))
                    .setTimestamp(request.finished() == null ? Instant.now() : request.finished())
                    .build());
        }
        return List.of(fields(report.get(), request));
    }

    private static MessageEmbed fields(final UpdateReport report, final UpdateRequest request) {
        final net.dv8tion.jda.api.EmbedBuilder embed = new net.dv8tion.jda.api.EmbedBuilder()
                .setTitle(report.stage().headline())
                .setColor(colour(report.stage() == UpdateReport.Stage.FAILED))
                .setTimestamp(request.finished() == null ? Instant.now() : request.finished());

        if (!report.notes().isEmpty()) {
            embed.setDescription(truncate(String.join("\n", report.notes())));
        }

        // Inline, so three or four servers sit side by side rather than as a column of headings.
        // Discord caps an embed at 25 fields; four services and a bot cannot reach that, and the
        // guard is here because the day a fifth backend is added is not the day to discover it.
        int drawn = 0;
        for (final UpdateReport.ServiceLine line : report.services()) {
            if (drawn++ >= 24) {
                embed.addField("...", "and " + (report.services().size() - 24) + " more", false);
                break;
            }
            embed.addField(line.service(), body(line), true);
        }
        return embed.build();
    }

    /** One service's field: what state it is in, and what is moving under it. */
    private static String body(final UpdateReport.ServiceLine line) {
        final StringBuilder text = new StringBuilder(marker(line.state()))
                .append(' ').append(line.state().label());
        for (final UpdateReport.Change change : line.changes()) {
            text.append("\n`").append(change.artefact()).append("` ")
                    .append(change.from() == null ? change.to()
                            : change.from() + " → " + change.to());
        }
        if (line.detail() != null && !line.detail().isBlank()) {
            text.append('\n').append(line.detail());
        }
        // Discord's per-field limit. A failure message from Arcane carrying a cause chain is the
        // one thing here that can reach it.
        return text.length() > 1000 ? text.substring(0, 997) + "..." : text.toString();
    }

    /**
     * One character in front of a state, so a run can be read without reading it.
     *
     * <p>Deliberately not colour: an embed has one colour for the whole of it, and the interesting
     * case is a run where three services are fine and the fourth is not.</p>
     */
    private static String marker(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> "\u2013";
            case PLANNED -> "\u25cb";
            case STOPPED, INSTALLED, STARTING -> "\u25d1";
            case HEALTHY -> "\u2714";
            case FAILED -> "\u2716";
        };
    }

    /**
     * Red for a failure, grey otherwise.
     *
     * <p>Deliberately not green for "an update is available": that is neither good news nor bad
     * news, it is just news.</p>
     */
    private static Color colour(final boolean failed) {
        return failed ? new Color(0xC0, 0x39, 0x2B) : new Color(0x99, 0xAA, 0xB5);
    }

    private static String truncate(final String text) {
        return text.length() > DESCRIPTION_BUDGET
                ? text.substring(0, DESCRIPTION_BUDGET)
                        + "\n... truncated; the updater's log has all of it"
                : text;
    }

    /**
     * The heading, for a row this class cannot parse into a report.
     *
     * <p>A parsed report titles itself from its own stage, which is the case that matters - the
     * stage moves as the run works and the heading has to move with it. This is only reached by
     * the plain-text rows written before 2026-09-07.</p>
     */
    private static String title(final UpdateRequest request) {
        if (request.status() == UpdateStatus.CANCELLED) {
            return "Stopped";
        }
        return switch (request.kind()) {
            case REPORT -> "What is new";
            case UPDATE -> "Update";
            // Retired; only rows written before 2026-09-07 carry it, and they are history.
            case APPLY -> "Installed";
            case RESTART -> "Restart";
        };
    }

    private static String timedOut(final UpdateRequest request) {
        // "Nothing was changed" is only true of a PENDING row. A RUNNING one means the updater
        // claimed the request and did not come back: it may have stopped servers and moved jars
        // already, and telling an admin nothing happened is the worst thing to say at that moment.
        // The timeout is this bot's patience, not a statement about the run. Found by review.
        final String state = request.status() == UpdateStatus.PENDING
                ? "Nothing was changed - nothing ever claimed it."
                : "It was claimed and did not finish, so servers may be stopped and jars may"
                        + " already have moved. Read the updater's log before doing anything else.";
        return "The updater has not answered in " + PATIENCE.toMinutes() + " minutes. The request "
                + "is still row " + request.id() + " in `update_request` and it is "
                + request.status() + ". " + state;
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
