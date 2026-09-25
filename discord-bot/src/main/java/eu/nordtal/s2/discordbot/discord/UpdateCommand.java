package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.commands.update.Refusals;
import eu.nordtal.s2.common.update.RunRefused;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

/**
 * {@code /update} - what is new, install it, restart the network.
 *
 * <p>The bot updates nothing and could not: steward-worker is a different container with the volumes
 * mounted. This class writes a row into {@code update_request} and reads the answer back, so every
 * fact an admin sees is the worker's own report rather than a second opinion.</p>
 *
 * <p>Two clicks: {@code /update check} changes nothing, and <b>Update now</b> is the confirmation.
 * Behind it is one run - the worker resolves what is new, and only if there is anything does a
 * countdown begin, after which the affected servers are stopped, moved and started again. There is
 * deliberately no button that swaps jars into running servers.</p>
 *
 * <p>The report is drawn as one field per service, and every word around it comes from the message
 * bundle - the keys are {@code :commands}' own, so a run reads the same here and in chat.</p>
 *
 * <p>Nothing here blocks: an install takes minutes, so the answer is waited for by re-reading one
 * indexed row on the bot's existing timer. The wait gives up short of Discord's fifteen-minute
 * interaction token, so the last thing the admin sees is a sentence rather than a message that
 * stopped changing.</p>
 */
@Slf4j
public final class UpdateCommand extends ListenerAdapter {

    /** How often the answer row is re-read. One indexed lookup; a person is watching. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(2);

    /**
     * How long to wait for steward-worker before saying so - short of Discord's fifteen-minute
     * interaction token, so the message is still editable when the wait gives up.
     */
    private static final Duration PATIENCE = Duration.ofMinutes(12);

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
     * Follow a request a command has just written, and draw it. {@code /update} itself is declared
     * in {@code :commands}, so who may run it and what is said back are decided once; what is left
     * here is Discord's half - the polling, the embed and the buttons.
     *
     * @param user the asker, which on this surface always carries the interaction to edit
     * @param id   the request to follow
     */
    public void follow(final eu.nordtal.s2.commands.NordtalUser user, final long id) {
        if (!(user instanceof DiscordUser discord)) {
            // A NordtalUser that is not a Discord one carries no interaction to draw on. Only
            // reachable through a wiring mistake, not through a runtime condition.
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
            // The language of whoever clicked, not of whoever put the button there: an admin has
            // to read their own language even when a colleague opened the message.
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
                plain(hook, say(locale, MESSAGES.command().notAdmin()));
                return;
            }

            // Due immediately, whatever the kind: the countdown belongs to steward-worker and starts
            // only once it knows there is work, so a run that finds nothing counts nothing down.
            final UpdateRequest request =
                    updates.submit(kind, UpdateSource.DISCORD, userId, Duration.ZERO);

            if (kind.stopsServers()) {
                announceCountdown(hook, locale, userId, request);
            } else {
                plain(hook, say(locale, MESSAGES.update().waiting().check()));
            }
            watch(hook, locale, request, Instant.now().plus(PATIENCE));
        } catch (final RunRefused refused) {
            // One run in the whole network; the directory decides it for every source.
            plain(hook, say(locale, Refusals.of(refused)));
        } catch (final RuntimeException failure) {
            fail(hook, locale, "writing the " + kind + " request", failure);
        }
    }

    /**
     * What an admin sees before anything moves. The sentence says <em>if</em> there is anything to
     * install, because steward-worker resolves first and a run that finds nothing takes nothing down.
     *
     * <p>The admin-channel line beside it stays hardcoded English, like every {@code AdminLog}
     * line: that channel is an operational record read by whoever is on, not one reader's
     * surface.</p>
     */
    private void announceCountdown(final InteractionHook hook, final Locale locale,
                                   final String userId, final UpdateRequest request) {
        final long seconds = UpdateDirectory.UPDATE_COUNTDOWN.toSeconds();
        final String what = request.kind() == UpdateKind.RESTART ? "restart" : "update";

        // Data, like the embeds: who, what, and the one number that matters to anybody online.
        admin.note("<@" + userId + "> → **" + what + "**, " + seconds + " s countdown if there is work");

        hook.editOriginal(new MessageEditBuilder()
                        .setContent(say(locale, MESSAGES.update().countdown().started(seconds)))
                        .setEmbeds(List.of())
                        .setComponents(ActionRow.of(Button.secondary(Ids.UPDATE_CANCEL,
                                say(locale, MESSAGES.update().button().cancel()))))
                        .build())
                .queue();
    }

    private void cancel(final InteractionHook hook, final Locale locale,
                        final net.dv8tion.jda.api.entities.User user) {
        try {
            if (!dao.isAdmin(user.getId()).orElse(false)) {
                plain(hook, say(locale, MESSAGES.command().notAdmin()));
                return;
            }
            final Optional<UpdateRequest> cancelled = updates.cancelCountdown(
                    "Cancelled in Discord by " + user.getName());

            if (cancelled.isPresent()) {
                // Named from the row, not the button: one cancel serves both kinds, and the admin
                // log is what somebody reads weeks later to work out what happened.
                final String what = cancelled.get().kind() == UpdateKind.UPDATE
                        ? "update" : "restart";
                admin.note(user.getAsMention() + " → **" + what + " cancelled**");
                plain(hook, say(locale, MESSAGES.update().cancelled()));
            } else {
                plain(hook, say(locale, MESSAGES.update().tooLate()));
            }
        } catch (final RuntimeException failure) {
            fail(hook, locale, "cancelling the countdown", failure);
        }
    }

    // ---------------------------------------------------------------- reading the answer back

    /**
     * Re-reads the row until it reaches a terminal state, then edits the message. A rescheduled task
     * on the shared timer rather than a loop, so nothing is held while an install downloads.
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
                    plain(hook, say(locale, MESSAGES.update().gone()));
                    return;
                }
                final UpdateRequest current = row.get();
                if (current.status().isFinished()) {
                    hook.editOriginal(finished(current, locale)).queue();
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    plain(hook, say(locale, MESSAGES.update().timeout(current.status())));
                    return;
                }

                // The run rewrites its own report as it goes, and redrawing here is what turns
                // five minutes of silence into something a person can watch.
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

    private String say(final Locale locale, final MessageRef message) {
        return messages.format(locale, message);
    }

    /**
     * The language recorded for a Discord account, defaulting to English. Never Discord's own client
     * locale: {@code discord_user.locale} is the one place a person's language lives.
     */
    private Locale localeOf(final String discordId) {
        return Locales.parse(dao.localeOf(discordId).orElse(null));
    }

    /**
     * The finished request, as an embed with steward-worker's own report in it. Only a report that
     * found work offers a button; a finished update has nothing to follow it, and a failure leads
     * nowhere because the next thing to do is read what it says.
     */
    private MessageEditData finished(final UpdateRequest request, final Locale locale) {
        // A cancelled restart is not a failure - colouring it red would make a deliberate act look
        // like something that went wrong.
        final boolean failed = request.status() == UpdateStatus.FAILED;
        final MessageEditBuilder message = new MessageEditBuilder()
                .setContent("")
                .setEmbeds(embed(request, failed, locale));

        if (request.status() != UpdateStatus.DONE || request.kind() != UpdateKind.REPORT) {
            return message.setComponents(List.of()).build();
        }
        // A report that found nothing gets no button: "Update now" under a list of things that are
        // all current is an invitation to take four servers down for nothing.
        final boolean worth = UpdateReports.parse(request.result())
                .map(UpdateReport::isWork)
                .orElse(true);
        return worth
                ? message.setComponents(ActionRow.of(button(request.kind(), locale))).build()
                : message.setComponents(List.of()).build();
    }

    private Button button(final UpdateKind kind, final Locale locale) {
        return kind == UpdateKind.REPORT
                ? Button.danger(Ids.UPDATE_INSTALL, say(locale, MESSAGES.update().button().install()))
                : Button.danger(Ids.UPDATE_RESTART, say(locale, MESSAGES.update().button().restart()));
    }

    /**
     * steward-worker's report, drawn as an embed: one inline field per service, with the description
     * carrying only what belongs to no service.
     *
     * <p>Older rows in the deployed database hold plain text instead. {@link UpdateReports#parse}
     * answers empty for those and the code-fence rendering is used. Nothing is migrated - a
     * finished request is never read twice.</p>
     */
    private List<MessageEmbed> embed(final UpdateRequest request, final boolean failed,
                                     final Locale locale) {
        final String result = request.result();
        final Optional<UpdateReport> report = UpdateReports.parse(result);
        if (report.isEmpty()) {
            // A row from before the report became structured. Drawn as its text, not as a code
            // block: nobody copies it anywhere.
            final Card card = Card.of(title(request, locale), failed ? Card.Accent.BAD : Card.Accent.NEUTRAL)
                    .timestamp(request.finished() == null ? Instant.now() : request.finished());
            if (result != null && !result.isBlank()) {
                card.lead(Card.escape(result));
            }
            return List.of(card.build());
        }
        return List.of(fields(report.get(), request, messages, locale));
    }

    // Package-private so EmbedBudgetTest can build one and measure it: Discord's 6000 is a limit
    // on the whole embed, and every guard here is arithmetic best measured rather than reasoned.
    static MessageEmbed fields(final UpdateReport report, final UpdateRequest request,
                               final Messages messages, final Locale locale) {
        return fields(report, request, messages, locale, false);
    }

    /**
     * One run, drawn as data: the stage as the title, the outcome as the colour, one line per
     * service under one heading, and what went wrong under another.
     *
     * <p>One line per service in one block, not one field per service: a run with ten services and
     * three artefacts each is the case Discord's 25 fields and 6000 characters were hit by, and
     * {@link Card#block} counts what does not fit rather than cutting it off.</p>
     *
     * @param context whether to say who asked, and from where. Only the admin channel's feed does:
     *                the asker's own embed is theirs, and knows
     */
    static MessageEmbed fields(final UpdateReport report, final UpdateRequest request,
                               final Messages messages, final Locale locale, final boolean context) {
        final Card card = Card.of(messages.format(locale, MESSAGES.update().stage(report.stage())),
                        accent(report.stage()))
                .timestamp(request.finished() == null ? Instant.now() : request.finished());
        final java.util.function.IntFunction<String> more =
                count -> Card.italic(messages.format(locale, MESSAGES.update().embed().more(count)));

        if (context) {
            card.field(messages.format(locale, MESSAGES.update().embed().run()),
                            request.kind().name().toLowerCase(Locale.ROOT))
                    .field(messages.format(locale, MESSAGES.update().embed().by()),
                            request.requestedBy() == null ? "console" : Card.escape(request.requestedBy()))
                    .field(messages.format(locale, MESSAGES.update().embed().from()),
                            request.source().name().toLowerCase(Locale.ROOT));
        }
        if (request.finished() != null && request.requested() != null) {
            card.field(messages.format(locale, MESSAGES.update().embed().duration()),
                    Card.duration(Duration.between(request.requested(), request.finished())));
        }

        // The services get the budget first and the notes what is left: a run with long notes is
        // usually a failed one, and "which server did not come back" matters more.
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final java.util.List<String> notes = new java.util.ArrayList<>();
        for (final UpdateReport.ServiceLine line : report.services()) {
            lines.add(line(line, messages, locale));
            if (line.detail() != null && !line.detail().isBlank()) {
                notes.add(Card.bold(line.service()) + " " + Card.escape(line.detail()));
            }
        }
        for (final String note : report.notes()) {
            // A note of several lines is a page rendered for a terminal - steward-worker's "what was
            // done" table - and says again what the service lines above already say, in a shape
            // that only reads in a monospaced font. Cut into a field it is a wall of text; Steward's
            // log has it in full.
            if (!note.isBlank() && note.strip().indexOf('\n') < 0) {
                notes.add(Card.escape(note.strip()));
            }
        }
        card.block(messages.format(locale, MESSAGES.update().embed().services()), lines, more);
        card.block(messages.format(locale, MESSAGES.update().embed().notes()), notes, more);
        return card.build();
    }

    /**
     * {@code ✔ smp running  smp 0.9.3 → 0.9.4}: the marker so a run reads at a glance, the service
     * in bold because it is what a reader scans for, the state in italic because the marker has
     * already said it, and every artefact that moves as a transition.
     */
    private static String line(final UpdateReport.ServiceLine line, final Messages messages,
                               final Locale locale) {
        final StringBuilder text = new StringBuilder(marker(line.state())).append(' ')
                .append(Card.bold(line.service())).append(' ')
                .append(Card.italic(messages.format(locale, MESSAGES.update().state(line.state()))));
        for (final UpdateReport.Change change : line.changes()) {
            text.append("  ").append(Card.escape(change.artefact())).append(' ').append(switch (change.state()) {
                // An artefact whose publisher has no build for this Minecraft version. Not a
                // failure: no server is stopped for it and nothing beside it is held back.
                case UNSUPPORTED -> Card.italic(messages.format(locale, MESSAGES.update().embed().noBuild()));
                case MOVING -> change.from() == null ? Card.bold(change.to()) : Card.arrow(change.from(), change.to());
            });
        }
        return text.toString();
    }

    /**
     * One character in front of a state, so a run can be read at a glance. The embed's colour is
     * the run's outcome; this is each service's, because the interesting case is three services
     * fine and the fourth not.
     */
    private static String marker(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> "–";
            case PLANNED -> "○";
            case STOPPED, INSTALLED, STARTING -> "◑";
            // A finished snapshot and a service that came back are the same news to a reader.
            case HEALTHY, SAVED -> "✔";
            case FAILED -> "✖";
        };
    }

    /**
     * Red for a failure, green for a run that did what it was asked, grey for everything still
     * moving or with nothing to say - "an update is available" is neither good nor bad news.
     */
    private static Card.Accent accent(final UpdateReport.Stage stage) {
        return switch (stage) {
            case FAILED -> Card.Accent.BAD;
            case DONE -> Card.Accent.GOOD;
            default -> Card.Accent.NEUTRAL;
        };
    }

    /**
     * The heading for a row this class cannot parse into a report. A parsed report titles itself
     * from its own stage, which moves as the run works.
     */
    private String title(final UpdateRequest request, final Locale locale) {
        return request.status() == UpdateStatus.CANCELLED
                ? say(locale, MESSAGES.update().stage().cancelled())
                // Retired kinds still need a heading: their old rows are still readable.
                : say(locale, MESSAGES.update().title(request.kind()));
    }

    /**
     * One place for "that did not work": the admin gets a plain sentence, the admin channel gets
     * the detail. This surface moves jars on every server, so a failure nobody sees is the one
     * thing it must not produce.
     */
    private void fail(final InteractionHook hook, final Locale locale, final String what,
                      final RuntimeException failure) {
        log.error("An update interaction failed while {}", what, failure);
        admin.alert("An update interaction failed while " + what + ": `" + failure + "`");
        plain(hook, say(locale, MESSAGES.update().interactionFailed()));
    }
}
