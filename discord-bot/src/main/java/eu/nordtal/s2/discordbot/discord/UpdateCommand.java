package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.update.Refusals;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.Card;
import eu.nordtal.s2.discordbot.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * {@code /update}: writes a row into {@code update_request} and draws steward-worker's answer.
 *
 * Nothing blocks: the row is re-read on the bot's timer, giving up short of the fifteen-minute interaction token.
 */
@Slf4j
public final class UpdateCommand extends ListenerAdapter {

    /** How often the answer row is re-read. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(2);

    /** How long to wait for steward-worker, short of Discord's fifteen-minute interaction token. */
    private static final Duration PATIENCE = Duration.ofMinutes(12);

    private final UpdateDirectory updates;
    private final AdminLog admin;
    private final AdminFlagDao dao;
    private final Messages messages;
    private final ExecutorService worker;
    private final ScheduledExecutorService timers;

    /**
     * Creates the command over the bot's layered bundle.
     *
     * @param messages the bot's layered bundle, in which every {@code update.*} key is declared
     */
    public UpdateCommand(
            final UpdateDirectory updates,
            final AdminLog admin,
            final Jdbi jdbi,
            final Messages messages,
            final ExecutorService worker,
            final ScheduledExecutorService timers) {
        this.updates = updates;
        this.admin = admin;
        this.dao = jdbi.onDemand(AdminFlagDao.class);
        this.messages = Objects.requireNonNull(messages, "messages");
        this.worker = worker;
        this.timers = timers;
    }

    /**
     * Follows a request a command has just written, and draws it.
     *
     * @param user the asker, which on this surface always carries the interaction to edit
     * @param id the request to follow
     */
    public void follow(final eu.nordtal.s2.commands.NordtalUser user, final long id) {
        if (!(user instanceof DiscordUser discord)) {
            // Only a wiring bug reaches here: a non-Discord user has no interaction to draw on.
            log.warn(
                    "An update was asked for through the Discord effects by a {}, which carries no"
                            + " interaction to draw on. Request {} still ran.",
                    user.getClass(),
                    id);
            return;
        }
        updates.find(id)
                .ifPresent(request -> watch(
                        discord.hook(), discord.locale(), request, Instant.now().plus(PATIENCE)));
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (!Ids.UPDATE_INSTALL.equals(id) && !Ids.UPDATE_RESTART.equals(id) && !Ids.UPDATE_CANCEL.equals(id)) {
            // Every other flow's buttons come through here too.
            return;
        }

        event.deferEdit().queue();
        worker.execute(() -> {
            // The language of whoever clicked, who may not be whoever opened it.
            final Locale locale = localeOf(event.getUser().getId());
            if (Ids.UPDATE_CANCEL.equals(id)) {
                cancel(event.getHook(), locale, event.getUser());
                return;
            }
            submit(
                    event.getHook(),
                    locale,
                    event.getUser().getId(),
                    Ids.UPDATE_INSTALL.equals(id) ? UpdateKind.UPDATE : UpdateKind.RESTART);
        });
    }

    private void submit(final InteractionHook hook, final Locale locale, final String userId, final UpdateKind kind) {
        try {
            // Checked on every click: a confirmation can outlive the role.
            if (!dao.isAdmin(userId).orElse(false)) {
                plain(hook, say(locale, MESSAGES.command().notAdmin()));
                return;
            }

            // Due immediately: steward-worker starts the countdown once it knows there is work.
            final UpdateRequest request = updates.submit(kind, UpdateSource.DISCORD, userId, Duration.ZERO);

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

    /** Tells the admin what happens before anything moves, and notes it in English in the admin channel. */
    private void announceCountdown(
            final InteractionHook hook, final Locale locale, final String userId, final UpdateRequest request) {
        final long seconds = UpdateDirectory.UPDATE_COUNTDOWN.toSeconds();
        final String what = request.kind() == UpdateKind.RESTART ? "restart" : "update";

        // Who, what, and the one number that matters to anybody online.
        admin.note("<@" + userId + "> → **" + what + "**, " + seconds + " s countdown if there is work");

        hook.editOriginal(new MessageEditBuilder()
                        .setContent(say(locale, MESSAGES.update().countdown().started(seconds)))
                        .setEmbeds(List.of())
                        .setComponents(ActionRow.of(Button.secondary(
                                Ids.UPDATE_CANCEL,
                                say(locale, MESSAGES.update().button().cancel()))))
                        .build())
                .queue();
    }

    private void cancel(final InteractionHook hook, final Locale locale, final net.dv8tion.jda.api.entities.User user) {
        try {
            if (!dao.isAdmin(user.getId()).orElse(false)) {
                plain(hook, say(locale, MESSAGES.command().notAdmin()));
                return;
            }
            final Optional<UpdateRequest> cancelled =
                    updates.cancelCountdown("Cancelled in Discord by " + user.getName());

            if (cancelled.isPresent()) {
                // Named from the row, not the button: one cancel serves both kinds.
                final String what = cancelled.get().kind() == UpdateKind.UPDATE ? "update" : "restart";
                admin.note(user.getAsMention() + " → **" + what + " cancelled**");
                plain(hook, say(locale, MESSAGES.update().cancelled()));
            } else {
                plain(hook, say(locale, MESSAGES.update().tooLate()));
            }
        } catch (final RuntimeException failure) {
            fail(hook, locale, "cancelling the countdown", failure);
        }
    }

    /** Re-reads the row on the shared timer until it is terminal, then edits the message. */
    private void watch(
            final InteractionHook hook, final Locale locale, final UpdateRequest request, final Instant deadline) {
        watch(hook, locale, request, deadline, null);
    }

    /**
     * Re-reads the row, editing the embed only when the report changed.
     *
     * @param drawn the report text this message is currently showing
     */
    private void watch(
            final InteractionHook hook,
            final Locale locale,
            final UpdateRequest request,
            final Instant deadline,
            final @Nullable String drawn) {
        // The runnable catches every exception itself.
        var _ = timers.schedule(
                () -> {
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

                        // The run rewrites its own report as it goes.
                        String showing = drawn;
                        final String progress = current.result();
                        if (progress != null && !progress.equals(drawn)) {
                            UpdateReports.parse(progress)
                                    .ifPresent(report -> hook.editOriginal(new MessageEditBuilder()
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
                },
                CHECK_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** Answers with one line, no embed and no buttons. */
    private static void plain(final InteractionHook hook, final String text) {
        hook.editOriginal(text).setEmbeds(List.of()).setComponents(List.of()).queue();
    }

    private String say(final Locale locale, final MessageRef message) {
        return messages.format(locale, message);
    }

    /** Returns the language recorded for a Discord account, never Discord's client locale. */
    private Locale localeOf(final String discordId) {
        return Locales.parse(dao.localeOf(discordId).orElse(null));
    }

    /** Draws the finished request; only a report that found work offers a button. */
    private MessageEditData finished(final UpdateRequest request, final Locale locale) {
        // A cancelled restart is deliberate, not a failure.
        final boolean failed = request.status() == UpdateStatus.FAILED;
        final MessageEditBuilder message =
                new MessageEditBuilder().setContent("").setEmbeds(embed(request, failed, locale));

        if (request.status() != UpdateStatus.DONE || request.kind() != UpdateKind.REPORT) {
            return message.setComponents(List.of()).build();
        }
        // "Update now" under a fully current list would invite a needless run.
        final boolean worth =
                UpdateReports.parse(request.result()).map(UpdateReport::isWork).orElse(true);
        return worth
                ? message.setComponents(ActionRow.of(button(request.kind(), locale)))
                        .build()
                : message.setComponents(List.of()).build();
    }

    private Button button(final UpdateKind kind, final Locale locale) {
        return kind == UpdateKind.REPORT
                ? Button.danger(
                        Ids.UPDATE_INSTALL,
                        say(locale, MESSAGES.update().button().install()))
                : Button.danger(
                        Ids.UPDATE_RESTART,
                        say(locale, MESSAGES.update().button().restart()));
    }

    /** Draws steward-worker's report, falling back to text for a row whose report is not structured. */
    private List<MessageEmbed> embed(final UpdateRequest request, final boolean failed, final Locale locale) {
        final String result = request.result();
        final Optional<UpdateReport> report = UpdateReports.parse(result);
        if (report.isEmpty()) {
            // A row whose report is not structured, drawn as text.
            final Card card = Card.of(title(request, locale), failed ? Card.Accent.BAD : Card.Accent.NEUTRAL)
                    .timestamp(request.finished() == null ? Instant.now() : request.finished());
            if (result != null && !result.isBlank()) {
                card.lead(Card.escape(result));
            }
            return List.of(card.build());
        }
        return List.of(fields(report.get(), request, messages, locale));
    }

    static MessageEmbed fields(
            final UpdateReport report, final UpdateRequest request, final Messages messages, final Locale locale) {
        return fields(report, request, messages, locale, false);
    }

    /**
     * Draws one run as data: the stage as title, the outcome as colour, one line per service.
     *
     * @param context whether to say who asked, and from where; only the admin channel's feed does
     */
    static MessageEmbed fields(
            final UpdateReport report,
            final UpdateRequest request,
            final Messages messages,
            final Locale locale,
            final boolean context) {
        final Card card = Card.of(
                        messages.format(locale, MESSAGES.update().stage(report.stage())), accent(report.stage()))
                .timestamp(request.finished() == null ? Instant.now() : request.finished());
        final java.util.function.IntFunction<String> more = count ->
                Card.italic(messages.format(locale, MESSAGES.update().embed().more(count)));

        if (context) {
            card.field(
                            messages.format(locale, MESSAGES.update().embed().run()),
                            request.kind().name().toLowerCase(Locale.ROOT))
                    .field(
                            messages.format(locale, MESSAGES.update().embed().by()),
                            request.requestedBy() == null ? "console" : Card.escape(request.requestedBy()))
                    .field(
                            messages.format(locale, MESSAGES.update().embed().from()),
                            request.source().name().toLowerCase(Locale.ROOT));
        }
        if (request.finished() != null && request.requested() != null) {
            card.field(
                    messages.format(locale, MESSAGES.update().embed().duration()),
                    Card.duration(Duration.between(request.requested(), request.finished())));
        }

        // The services get the budget first: which server failed matters more than why.
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final java.util.List<String> notes = new java.util.ArrayList<>();
        for (final UpdateReport.ServiceLine line : report.services()) {
            lines.add(line(line, messages, locale));
            if (line.detail() != null && !line.detail().isBlank()) {
                notes.add(Card.bold(line.service()) + " " + Card.escape(line.detail()));
            }
        }
        for (final String note : report.notes()) {
            // A multi-line note only reads in monospace and repeats the service lines.
            if (!note.isBlank() && note.strip().indexOf('\n') < 0) {
                notes.add(Card.escape(note.strip()));
            }
        }
        card.block(messages.format(locale, MESSAGES.update().embed().services()), lines, more);
        card.block(messages.format(locale, MESSAGES.update().embed().notes()), notes, more);
        return card.build();
    }

    /** Renders a service line such as {@code ✔ smp running  smp 0.9.3 → 0.9.4}. */
    private static String line(final UpdateReport.ServiceLine line, final Messages messages, final Locale locale) {
        final StringBuilder text = new StringBuilder(marker(line.state()))
                .append(' ')
                .append(Card.bold(line.service()))
                .append(' ')
                .append(Card.italic(messages.format(locale, MESSAGES.update().state(line.state()))));
        for (final UpdateReport.Change change : line.changes()) {
            text.append("  ")
                    .append(Card.escape(change.artefact()))
                    .append(' ')
                    .append(
                            switch (change.state()) {
                                // No build for this Minecraft version, which stops no server.
                                case UNSUPPORTED ->
                                    Card.italic(messages.format(
                                            locale, MESSAGES.update().embed().noBuild()));
                                case MOVING ->
                                    change.from() == null
                                            ? Card.bold(change.to())
                                            : Card.arrow(change.from(), change.to());
                            });
        }
        return text.toString();
    }

    /** Returns the marker of one service's state. */
    private static String marker(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> "\u2013";
            case PLANNED -> "○";
            case STOPPED, INSTALLED, STARTING -> "◑";
            // A finished snapshot and a service that came back are the same news.
            case HEALTHY, SAVED -> "✔";
            case FAILED -> "✖";
        };
    }

    /** Returns red for a failure, green for success, and grey for everything else. */
    private static Card.Accent accent(final UpdateReport.Stage stage) {
        return switch (stage) {
            case FAILED -> Card.Accent.BAD;
            case DONE -> Card.Accent.GOOD;
            default -> Card.Accent.NEUTRAL;
        };
    }

    /** Returns the heading for a row that cannot be parsed into a report. */
    private String title(final UpdateRequest request, final Locale locale) {
        return request.status() == UpdateStatus.CANCELLED
                ? say(locale, MESSAGES.update().stage().cancelled())
                // Retired kinds still have readable rows.
                : say(locale, MESSAGES.update().title(request.kind()));
    }

    /** Tells the admin in one sentence and the admin channel in detail that something failed. */
    private void fail(
            final InteractionHook hook, final Locale locale, final String what, final RuntimeException failure) {
        log.error("An update interaction failed while {}", what, failure);
        admin.alert("An update interaction failed while " + what + ": `" + failure + "`");
        plain(hook, say(locale, MESSAGES.update().interactionFailed()));
    }
}
