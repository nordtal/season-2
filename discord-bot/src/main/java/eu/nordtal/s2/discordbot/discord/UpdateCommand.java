package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
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
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * fact an admin sees here is the updater's own report - not a second opinion that could disagree
 * with the first.
 *
 * <h2>Two clicks: look, then confirm</h2>
 * {@code /update check} reports and changes nothing. <b>Update now</b> is the confirmation, and
 * behind it is one run: the updater resolves what is new, and only if there is anything does a
 * countdown every player on the network sees begin. Then the servers whose jars change are stopped,
 * the schema and the jars are moved with nothing running on them, and each server is started again
 * and watched until it reports healthy.
 *
 * <p>It used to be three clicks, the middle one being <em>install</em> - which swapped jars into
 * running servers and is finding 147. There is no button for that any more, because the button was
 * the defect.</p>
 *
 * <h2>The report is drawn, not quoted - and it is drawn in the admin's language</h2>
 * Until 2026-09-07 the updater's whole report went into the embed inside a code fence, which is
 * unreadable at a glance and says nothing while a run is working. The updater now answers with an
 * {@code UpdateReport}, and this class draws it as one field per service.
 *
 * <p><b>Every word around it comes from the message bundle since 2026-09-08.</b> It did not: the
 * headings, the state labels, the buttons, the waiting lines and the failure sentences were all
 * hardcoded English in this file, so an admin whose {@code discord_user.locale} is German got
 * German for {@code /phase} and English for {@code /update}. The keys are {@code :commands}' own,
 * which is what makes the same run read the same way here and in chat.</p>
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

    /**
     * Discord's limit on <em>everything</em> in one embed added together.
     *
     * <h2>Why the per-part caps are not enough</h2>
     * The description may use its own 4 000 and each of up to 24 service fields may use 1 000, so
     * the parts can be individually legal and the whole still refused. JDA throws from
     * {@code build()} when that happens, the exception unwinds into {@code fail()}, and the admin
     * is told "that did not work" instead of being shown the run - on the exact runs that have the
     * most to say, which are the ones going wrong. Found by review, 2026-09-08.
     */
    private static final int EMBED_BUDGET = 6000;

    private final UpdateDirectory updates;
    private final AdminLog admin;
    private final AdminFlagDao dao;
    private final Messages messages;
    private final ExecutorService worker;
    private final ScheduledExecutorService timers;

    /**
     * @param messages the bot's layered bundle - {@code :commands}' shared file underneath this
     *                 module's own, which is what every {@code update.*} key below is declared in
     */
    public UpdateCommand(final UpdateDirectory updates, final AdminLog admin, final Jdbi jdbi,
                         final Messages messages, final ExecutorService worker,
                         final ScheduledExecutorService timers) {
        this.updates = updates;
        this.admin = admin;
        this.dao = jdbi.onDemand(AdminFlagDao.class);
        this.messages = Objects.requireNonNull(messages, "messages");
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
                watch(discord.hook(), discord.locale(), request, Instant.now().plus(PATIENCE)));
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
            // The language of whoever clicked, not of whoever ran the command that put the button
            // there: an admin's own message has to be in their own language even when a colleague
            // opened it. Same read DiscordCommands#resolve makes, one indexed lookup.
            final Locale locale = localeOf(event.getUser().getId());
            if (Ids.UPDATE_CANCEL.equals(id)) {
                cancel(event.getHook(), locale, event.getUser());
                return;
            }
            submit(event.getHook(), locale, event.getUser().getId(),
                    Ids.UPDATE_INSTALL.equals(id) ? UpdateKind.UPDATE : UpdateKind.RESTART);
        });
    }

    // ---------------------------------------------------------------- writing the row

    private void submit(final InteractionHook hook, final Locale locale, final String userId,
                        final UpdateKind kind) {
        try {
            // Checked on every click and not only on the command: a confirmation can sit on screen
            // while the role is taken away, and these are the clicks that change something.
            if (!dao.isAdmin(userId).orElse(false)) {
                plain(hook, say(locale, "command.not-admin"));
                return;
            }

            // Due immediately, whatever the kind: the countdown is the updater's now, started on
            // the row it has claimed once it knows there is work to do. Setting it here meant the
            // ordinary run - the one where nothing is new - counted thirty seconds down to every
            // player on the network before answering "everything is already current".
            final UpdateRequest request =
                    updates.submit(kind, UpdateSource.DISCORD, userId, Duration.ZERO);

            if (kind.stopsServers()) {
                announceCountdown(hook, locale, userId, request);
            } else {
                plain(hook, say(locale, "update.waiting.check"));
            }
            watch(hook, locale, request, Instant.now().plus(PATIENCE));
        } catch (final RuntimeException failure) {
            fail(hook, locale, "writing the " + kind + " request", failure);
        }
    }

    /**
     * What an admin sees for the thirty seconds before anything moves.
     *
     * <h2>The wording, and why it changed twice</h2>
     * It read <em>"Everybody online is being counted down and it happens in 60 seconds"</em>, which
     * says a thing to a person rather than about one, and promised a restart of "the whole network"
     * that the run no longer performs - only the servers whose jars actually change are stopped.
     *
     * <p>It is now a message key, and the sentence says <em>if</em> there is anything to install,
     * because as of 2026-09-08 the updater resolves first and only starts a countdown when the plan
     * has work in it. A run that finds nothing new takes nothing down and counts nothing down.</p>
     *
     * <p>The admin-channel line beside it stays hardcoded English, like every other
     * {@code AdminLog} line in this bot: that channel is an operational record read by whoever is
     * on, not a surface with one reader's language.</p>
     */
    private void announceCountdown(final InteractionHook hook, final Locale locale,
                                   final String userId, final UpdateRequest request) {
        final long seconds = UpdateDirectory.UPDATE_COUNTDOWN.toSeconds();
        final String what = request.kind() == UpdateKind.RESTART ? "a restart" : "an update";

        admin.note("<@" + userId + "> started " + what + ". If the updater finds anything to do,"
                + " every player online sees a " + seconds + "-second countdown, and the servers"
                + " involved are stopped, "
                + (request.kind() == UpdateKind.RESTART ? "" : "updated ") + "and started again"
                + " after it.");

        hook.editOriginal(new MessageEditBuilder()
                        .setContent(say(locale, "update.countdown.started",
                                Map.of("seconds", seconds)))
                        .setEmbeds(List.of())
                        .setComponents(ActionRow.of(Button.secondary(Ids.UPDATE_CANCEL,
                                say(locale, "update.button.cancel"))))
                        .build())
                .queue();
    }

    private void cancel(final InteractionHook hook, final Locale locale,
                        final net.dv8tion.jda.api.entities.User user) {
        try {
            if (!dao.isAdmin(user.getId()).orElse(false)) {
                plain(hook, say(locale, "command.not-admin"));
                return;
            }
            final Optional<UpdateRequest> cancelled = updates.cancelCountdown(
                    "Cancelled in Discord by " + user.getName());

            if (cancelled.isPresent()) {
                // Named from the row, not from the button: since 2026-09-08 the same countdown and
                // the same cancel serve an UPDATE as well as a RESTART, and saying "the restart"
                // for an update that was about to replace jars is the wrong thing in the admin log
                // - which is the record somebody reads weeks later to work out what happened.
                final String what = cancelled.get().kind() == UpdateKind.UPDATE
                        ? "update" : "restart";
                admin.note(user.getAsMention() + " stopped the " + what + " before it happened.");
                plain(hook, say(locale, "update.cancelled"));
            } else {
                plain(hook, say(locale, "update.too-late"));
            }
        } catch (final RuntimeException failure) {
            fail(hook, locale, "cancelling the countdown", failure);
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
    private void watch(final InteractionHook hook, final Locale locale,
                       final UpdateRequest request, final Instant deadline) {
        watch(hook, locale, request, deadline, null);
    }

    /**
     * @param drawn the report text this message is currently showing, so that the embed is only
     *              edited when it has something new to say. Discord rate-limits message edits, and
     *              a run writes a stage at a time while this polls every two seconds - re-sending
     *              an identical embed twenty times between two stages would spend that budget on
     *              nothing
     */
    private void watch(final InteractionHook hook, final Locale locale, final UpdateRequest request,
                       final Instant deadline, final String drawn) {
        timers.schedule(() -> {
            try {
                final Optional<UpdateRequest> row = updates.find(request.id());
                if (row.isEmpty()) {
                    plain(hook, say(locale, "update.gone"));
                    return;
                }
                final UpdateRequest current = row.get();
                if (current.status().isFinished()) {
                    hook.editOriginal(finished(current, locale)).queue();
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    plain(hook, say(locale, "update.timeout",
                            Map.of("status", current.status())));
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
                                    .setEmbeds(List.of(fields(report, current, messages, locale)))
                                    .setComponents(List.of())
                                    .build())
                            .queue());
                    showing = progress;
                }
                watch(hook, locale, request, deadline, showing);
            } catch (final RuntimeException failure) {
                fail(hook, locale, "reading the answer to request " + request.id(), failure);
            }
        }, CHECK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- what an admin sees

    /** One line, no embed, no buttons - the shape every terminal sentence here uses. */
    private static void plain(final InteractionHook hook, final String text) {
        hook.editOriginal(text).setEmbeds(List.of()).setComponents(List.of()).queue();
    }

    private String say(final Locale locale, final String key) {
        return messages.format(locale, key, Map.of());
    }

    private String say(final Locale locale, final String key, final Map<String, ?> placeholders) {
        return messages.format(locale, key, placeholders);
    }

    /**
     * The language recorded for a Discord account, defaulting to English.
     *
     * <p>{@code discord_user.locale}, the same source {@code DiscordCommands#resolve} reads and the
     * same one a player's chat is rendered against. Never Discord's own client locale: docs/i18n.md
     * settles that the database is the one place a person's language lives.</p>
     */
    private Locale localeOf(final String discordId) {
        return Locales.parse(dao.localeOf(discordId).orElse(null));
    }

    /**
     * The finished request, as an embed with the updater's own report in it.
     *
     * <p>The button offered afterwards is the honest next step and nothing more: a report that
     * found work leads to the run that does it, and nothing else offers a button - a finished
     * update has nothing to follow it, and a failure leads nowhere at all, because the next thing
     * to do is read what it says.</p>
     */
    private MessageEditData finished(final UpdateRequest request, final Locale locale) {
        // A cancelled restart is not a failure - it is somebody using the way out on purpose, and
        // the watch below overwrites the "Stopped." line with this embed either way. Colouring it
        // red would turn a deliberate act into something that looks like it went wrong.
        final boolean failed = request.status() == UpdateStatus.FAILED;
        final MessageEditBuilder message = new MessageEditBuilder()
                .setContent("")
                .setEmbeds(embed(request, failed, locale));

        if (request.status() != UpdateStatus.DONE || request.kind() != UpdateKind.REPORT) {
            return message.setComponents(List.of()).build();
        }
        // A report that found nothing gets no button either: "Update now" under a list of things
        // that are all current is an invitation to take four servers down for nothing.
        final boolean worth = UpdateReports.parse(request.result())
                .map(UpdateReport::isWork)
                .orElse(true);
        return worth
                ? message.setComponents(ActionRow.of(button(request.kind(), locale))).build()
                : message.setComponents(List.of()).build();
    }

    private Button button(final UpdateKind kind, final Locale locale) {
        return kind == UpdateKind.REPORT
                ? Button.danger(Ids.UPDATE_INSTALL, say(locale, "update.button.install"))
                : Button.danger(Ids.UPDATE_RESTART, say(locale, "update.button.restart"));
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
    private List<MessageEmbed> embed(final UpdateRequest request, final boolean failed,
                                     final Locale locale) {
        final String result = request.result();
        final Optional<UpdateReport> report = UpdateReports.parse(result);
        if (report.isEmpty()) {
            return List.of(new net.dv8tion.jda.api.EmbedBuilder()
                    .setTitle(title(request, locale))
                    .setDescription("```\n" + truncate(result == null
                            ? "(the updater wrote nothing)" : result) + "\n```")
                    .setColor(colour(failed))
                    .setTimestamp(request.finished() == null ? Instant.now() : request.finished())
                    .build());
        }
        return List.of(fields(report.get(), request, messages, locale));
    }

    // Package-private so EmbedBudgetTest can build one and measure it: Discord's 6000 is a
    // limit on the whole embed, and every guard here is arithmetic that has already been wrong
    // twice.
    static MessageEmbed fields(final UpdateReport report, final UpdateRequest request,
                               final Messages messages, final Locale locale) {
        final String headline = messages.format(locale, "update.stage." + report.stage(), Map.of());
        final net.dv8tion.jda.api.EmbedBuilder embed = new net.dv8tion.jda.api.EmbedBuilder()
                .setTitle(headline)
                .setColor(colour(report.stage() == UpdateReport.Stage.FAILED))
                .setTimestamp(request.finished() == null ? Instant.now() : request.finished());

        // The service lines are what somebody is actually watching, so they get the budget first
        // and the notes get what is left. A run whose notes are long is usually a run that failed,
        // and "which server did not come back" is the half that matters then.
        int budget = EMBED_BUDGET - headline.length();
        final java.util.List<String[]> drawn = new java.util.ArrayList<>();
        for (final UpdateReport.ServiceLine line : report.services()) {
            // Discord caps an embed at 25 fields; four services and a bot cannot reach that, and
            // the guard is here because the day a fifth backend is added is not the day to find out.
            if (drawn.size() >= 24) {
                break;
            }
            final String value = body(line, messages, locale);
            final int cost = line.service().length() + value.length();
            if (cost > budget) {
                break;
            }
            budget -= cost;
            drawn.add(new String[] {line.service(), value});
        }

        if (!report.notes().isEmpty() && budget > 0) {
            final String notes = String.join("\n", report.notes());
            // Subtracted, not just bounded. The description consumes up to the whole remaining
            // budget, and leaving `budget` unchanged here let the overflow field below measure
            // itself against space the description had already taken.
            final String description = truncate(notes, Math.min(DESCRIPTION_BUDGET, budget));
            embed.setDescription(description);
            budget -= description.length();
        }
        // Inline, so three or four servers sit side by side rather than as a column of headings.
        drawn.forEach(field -> embed.addField(field[0], field[1], true));
        if (drawn.size() < report.services().size()) {
            final int left = report.services().size() - drawn.size();
            // Measured, not estimated. The first version of this reserved a flat 20 characters and
            // then wrote a value nearly three times that - which is the same overflow bug one line
            // further down, introduced by the guard against it.
            final String overflow = "and " + left + " more - the updater's log has all of it";
            if ("...".length() + overflow.length() <= budget) {
                embed.addField("...", overflow, false);
            }
        }
        return embed.build();
    }

    /**
     * One service's field: what state it is in, and what is moving under it.
     *
     * <p>The state label and the arrow between two versions come from the same bundle chat uses, so
     * a run reads the same in both places and in both languages. The label is {@code update.state.*}
     * rather than chat's {@code update.line.*} because a field already carries the service as its
     * heading, and "smp: stopped" under a heading reading "smp" is the name twice.</p>
     */
    private static String body(final UpdateReport.ServiceLine line, final Messages messages,
                               final Locale locale) {
        final StringBuilder text = new StringBuilder(marker(line.state())).append(' ')
                .append(messages.format(locale, "update.state." + line.state(), Map.of()));
        for (final UpdateReport.Change change : line.changes()) {
            text.append('\n').append(change.from() == null
                    ? messages.format(locale, "update.change.new", Map.of(
                            "artefact", change.artefact(), "to", change.to()))
                    : messages.format(locale, "update.change", Map.of(
                            "artefact", change.artefact(), "from", change.from(),
                            "to", change.to())));
        }
        if (line.detail() != null && !line.detail().isBlank()) {
            text.append('\n').append(messages.format(locale, "update.detail",
                    Map.of("detail", line.detail())));
        }
        // Discord's per-field limit. A failure message from Arcane carrying a cause chain is the
        // one thing here that can reach it.
        return text.length() > 1000 ? text.substring(0, 997) + "..." : text.toString();
    }

    /**
     * One character in front of a state, so a run can be read without reading it.
     *
     * <p>Deliberately not colour: an embed has one colour for the whole of it, and the interesting
     * case is a run where three services are fine and the fourth is not. It is also what carries
     * {@code Tone} across to this surface - the tone a chat line is painted with, as a glyph.</p>
     */
    private static String marker(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> "–";
            case PLANNED -> "○";
            case STOPPED, INSTALLED, STARTING -> "◑";
            case HEALTHY -> "✔";
            case FAILED -> "✖";
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
        return truncate(text, DESCRIPTION_BUDGET);
    }

    private static String truncate(final String text, final int budget) {
        final String tail = "\n... truncated; the updater's log has all of it";
        if (text.length() <= budget) {
            return text;
        }
        return budget <= tail.length() ? "" : text.substring(0, budget - tail.length()) + tail;
    }

    /**
     * The heading, for a row this class cannot parse into a report.
     *
     * <p>A parsed report titles itself from its own stage, which is the case that matters - the
     * stage moves as the run works and the heading has to move with it. This is only reached by
     * the plain-text rows written before 2026-09-07.</p>
     */
    private String title(final UpdateRequest request, final Locale locale) {
        return request.status() == UpdateStatus.CANCELLED
                ? say(locale, "update.stage.CANCELLED")
                // APPLY is retired; only rows written before 2026-09-07 carry it, and they are
                // history. It still needs a heading, because those rows are still readable.
                : say(locale, "update.title." + request.kind());
    }

    /**
     * One place for "that did not work".
     * <p>
     * The admin gets a plain sentence, the admin channel gets the detail. This is the surface that
     * moves jars on four servers; a failure nobody sees is the one thing it must not produce.
     * </p>
     */
    private void fail(final InteractionHook hook, final Locale locale, final String what,
                      final RuntimeException failure) {
        log.error("An update interaction failed while {}", what, failure);
        admin.alert("An update interaction failed while " + what + ": `" + failure + "`");
        plain(hook, say(locale, "update.interaction-failed"));
    }
}
