package eu.nordtal.s2.networkcontrol.phase;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.phase.SeasonDateRefused;
import eu.nordtal.s2.common.phase.SeasonDates;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /phase} and {@code /phase set <phase>} on the proxy - the <b>emergency</b> path for
 * switching the season phase when the bot or Discord is down. The normal path is
 * {@code /phase set} in Discord (docs/season-phases.md#who-may-switch-it).
 *
 * <h2>Decided, and not re-opened here</h2>
 * <ul>
 *   <li><b>Brigadier directly</b>, through {@code CommandManager.metaBuilder} - no command
 *       framework, {@code BasicCommand} is not used, and Brigadier is never shaded because Velocity
 *       provides {@code com.mojang.brigadier.*} at runtime. docs/architecture.md#commands.</li>
 *   <li><b>Authorised by {@code discord_user.admin}</b> - not by console and not by a Velocity
 *       permission node. "The same flag, read with the same query the login gate already makes",
 *       which is what {@link LoginRoster} holds. Console was considered and rejected on 2026-08-31:
 *       it would be a second, different notion of who may do this on a proxy that already knows
 *       exactly who is an admin. A non-player source therefore fails {@code requires} and the
 *       command does not exist for it.</li>
 *   <li><b>The switch goes through {@link PhaseDirectory#switchPhase}</b>, which writes the row, the
 *       {@code audit_log} entry and the {@code NOTIFY} as one statement. Nothing here writes the
 *       phase itself.</li>
 * </ul>
 *
 * <h2>What it cannot do</h2>
 * If the <em>database</em> is what is down, this command does not work either - the row lives
 * there. docs/season-phases.md says so in as many words and names the last resort: an {@code UPDATE}
 * by hand, which the proxy picks up on its next poll within thirty seconds. Authorisation still
 * works during an outage, because the admin flag was read at login and is held in memory; it is the
 * write that fails, and it fails loudly rather than silently.
 *
 * <h2>Threading</h2>
 * Every database call is handed to the proxy scheduler rather than made on the thread Brigadier
 * hands us, and {@code requires} is a map lookup for the same reason: Brigadier evaluates that
 * predicate while building the command tree it sends to a client, which is not a place for a
 * blocking JDBC call.
 */
public final class PhaseCommand {

    private static final String ALIAS = "phase";

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final PhaseDirectory phases;
    private final PhaseWatch watch;
    private final LoginRoster roster;
    private final Messages messages;

    public PhaseCommand(final Object plugin, final ProxyServer proxy, final Logger logger,
                        final PhaseDirectory phases, final PhaseWatch watch, final LoginRoster roster,
                        final Messages messages) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.phases = phases;
        this.watch = watch;
        this.roster = roster;
        this.messages = messages;
    }

    /** @return the command, ready to hand to {@code CommandManager#register(CommandMeta, Command)} */
    public BrigadierCommand build() {
        final LiteralArgumentBuilder<CommandSource> root = BrigadierCommand
                .literalArgumentBuilder(ALIAS)
                .requires(this::mayUse)
                .executes(this::report);

        final RequiredArgumentBuilder<CommandSource, String> phaseArgument = BrigadierCommand
                .requiredArgumentBuilder("phase", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (final SeasonPhase phase : SeasonPhase.values()) {
                        builder.suggest(phase.name());
                    }
                    return builder.buildFuture();
                })
                .executes(this::set);

        // greedyString, not word: a date carries a space, and Brigadier would otherwise hand over
        // "2026-10-01" and call "18:00" an unexpected second argument.
        return new BrigadierCommand(root
                .then(BrigadierCommand.literalArgumentBuilder("set").then(phaseArgument))
                .then(BrigadierCommand.literalArgumentBuilder("launch")
                        .then(dateArgument(Column.LAUNCH)))
                .then(BrigadierCommand.literalArgumentBuilder("smp-start")
                        .then(dateArgument(Column.SMP_START))));
    }

    private RequiredArgumentBuilder<CommandSource, String> dateArgument(final Column column) {
        return BrigadierCommand.requiredArgumentBuilder("when", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    builder.suggest(SeasonDates.CLEAR);
                    return builder.buildFuture();
                })
                .executes(context -> date(context, column));
    }

    /** @return the alias, so the caller can build the {@code CommandMeta} without repeating it */
    public static String alias() {
        return ALIAS;
    }

    // ---------------------------------------------------------------- authorisation

    /**
     * The whole authorisation rule: a connected player whose login query found
     * {@code discord_user.admin} set.
     * <p>
     * A non-{@link Player} source - the console - fails here and the command does not exist for it,
     * which is the rejection docs/season-phases.md records rather than an oversight. The lookup is
     * a {@link java.util.concurrent.ConcurrentHashMap} read because Brigadier evaluates this while
     * building the tree it sends to a client.
     * </p>
     */
    private boolean mayUse(final CommandSource source) {
        return source instanceof Player player && roster.isAdmin(player.getUniqueId());
    }

    // ---------------------------------------------------------------- the two branches

    private int report(final CommandContext<CommandSource> context) {
        final Player player = (Player) context.getSource();
        final Locale locale = roster.localeOf(player.getUniqueId());

        // Answered from the watch rather than with a query: this is the command somebody runs while
        // the network is misbehaving, and it should still say something useful when the database is
        // unreachable. Whether the value is an observation or the MAINTENANCE fallback is stated.
        player.sendMessage(watch.everRead()
                ? MessageRenderer.of(messages).format(locale, "phase.current",
                        "phase", watch.lastKnown().name())
                : MessageRenderer.of(messages).format(locale, "phase.current.unread",
                        "phase", watch.lastKnown().name()));

        // The dates come from the watch too, and only the opening is cached there - smp_start is
        // read fresh, off the calling thread, because nothing in this process needs it otherwise.
        proxy.getScheduler().buildTask(plugin, () -> {
            final String smpStart;
            try {
                smpStart = SeasonDates.format(phases.smpStart().orElse(null));
            } catch (final RuntimeException exception) {
                logger.warn("Could not read the season dates for /phase", exception);
                return;
            }
            player.sendMessage(MessageRenderer.of(messages).format(locale, "phase.dates",
                    "launch", SeasonDates.format(watch.launch().orElse(null)),
                    "smpStart", smpStart,
                    "zone", SeasonDates.ZONE.getId()));
        }).schedule();
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- the two dates

    /** Which column a {@code /phase launch} or {@code /phase smp-start} is about. */
    enum Column {

        LAUNCH("phase.date.what.launch"),
        SMP_START("phase.date.what.smp-start");

        private final String key;

        Column(final String key) {
            this.key = key;
        }
    }

    private int date(final CommandContext<CommandSource> context, final Column column) {
        final Player player = (Player) context.getSource();
        final Locale locale = roster.localeOf(player.getUniqueId());
        final String typed = StringArgumentType.getString(context, "when");

        final Instant at;
        if (SeasonDates.isClear(typed)) {
            at = null;
        } else {
            final var parsed = SeasonDates.parse(typed);
            if (parsed.isEmpty()) {
                player.sendMessage(MessageRenderer.of(messages).format(locale, "phase.date.invalid",
                        "pattern", SeasonDates.PATTERN,
                        "zone", SeasonDates.ZONE.getId(),
                        "clear", SeasonDates.CLEAR));
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }
            at = parsed.get();
        }

        final String actor = roster.of(player.getUniqueId())
                .map(LoginRoster.Session::discordId)
                .orElse(null);

        proxy.getScheduler().buildTask(plugin,
                () -> writeDate(player, locale, column, at, actor)).schedule();
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void writeDate(final Player player, final Locale locale, final Column column,
                           final Instant at, final String actor) {
        final DateChange change;
        try {
            change = column == Column.LAUNCH
                    ? phases.setLaunch(at, actor)
                    : phases.setSmpStart(at, actor);
        } catch (final SeasonDateRefused refused) {
            // The model said no, in a sentence written for the person who typed it.
            player.sendMessage(MessageRenderer.of(messages).format(locale, "phase.date.refused",
                    "reason", refused.getMessage()));
            return;
        } catch (final RuntimeException exception) {
            logger.error("{} could not write a season date from the proxy", player.getUsername(),
                    exception);
            player.sendMessage(MessageRenderer.of(messages).get(locale, "phase.date.failed"));
            return;
        }

        logger.warn("Season date written from the proxy by {} ({}): {} {} -> {}, {} grants moved",
                player.getUsername(), actor, column, change.previous(), change.current(),
                change.grants());

        // The opening lives in the watch, so refresh for the same reason the phase switch does.
        watch.refresh();

        final String what = messages.get(locale, column.key);
        if (change.current() == null) {
            player.sendMessage(MessageRenderer.of(messages).format(locale, "phase.date.cleared", "what", what));
            if (column == Column.SMP_START) {
                player.sendMessage(MessageRenderer.of(messages).get(locale, "phase.date.kept"));
            }
            return;
        }

        player.sendMessage(change.unchanged()
                ? MessageRenderer.of(messages).format(locale, "phase.date.unchanged",
                        "what", what, "current", SeasonDates.format(change.current()))
                : MessageRenderer.of(messages).format(locale, "phase.date.set",
                        "what", what,
                        "current", SeasonDates.format(change.current()),
                        "previous", SeasonDates.format(change.previous())));

        if (column == Column.SMP_START && change.movedAccess()) {
            player.sendMessage(MessageRenderer.of(messages).format(locale, "phase.date.moved",
                    "grants", String.valueOf(change.grants()),
                    "accounts", String.valueOf(change.accounts())));
        }
    }

    private int set(final CommandContext<CommandSource> context) {
        final Player player = (Player) context.getSource();
        final Locale locale = roster.localeOf(player.getUniqueId());
        final String requested = StringArgumentType.getString(context, "phase");

        final SeasonPhase target = parse(requested);
        if (target == null) {
            player.sendMessage(MessageRenderer.of(messages).format(locale, "phase.unknown",
                    "value", requested, "phases", names()));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        final String actor = roster.of(player.getUniqueId())
                .map(LoginRoster.Session::discordId)
                .orElse(null);

        // Off the calling thread: switchPhase is a blocking round trip, and this command may well
        // be run at the exact moment the database is slow.
        proxy.getScheduler().buildTask(plugin, () -> switchNow(player, locale, target, actor)).schedule();
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void switchNow(final Player player, final Locale locale, final SeasonPhase target,
                           final String actor) {
        final PhaseChange change;
        try {
            change = phases.switchPhase(target, actor,
                    "emergency switch from the proxy by " + player.getUsername());
        } catch (final RuntimeException exception) {
            logger.error("{} could not switch the season phase to {} from the proxy",
                    player.getUsername(), target, exception);
            player.sendMessage(MessageRenderer.of(messages).get(locale, "phase.failed"));
            return;
        }

        logger.warn("Season phase switched from the proxy by {} ({}): {} -> {}",
                player.getUsername(), actor, change.previous(), change.current());

        // Do not wait for the poll or the notification to come back around: this process already
        // knows, and refreshing here is what makes the reply and the log agree.
        watch.refresh();

        player.sendMessage(change.unchanged()
                ? MessageRenderer.of(messages).format(locale, "phase.unchanged",
                        "phase", change.current().name())
                : MessageRenderer.of(messages).format(locale, "phase.changed",
                        "previous", change.previous().name(), "current", change.current().name()));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Resolves what an admin typed, case-insensitively.
     *
     * @param value the raw argument
     * @return the phase, or {@code null} when it is not one
     */
    static SeasonPhase parse(final String value) {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase.name().equalsIgnoreCase(value)) {
                return phase;
            }
        }
        // Deliberately not SeasonPhase.fromDatabase: that maps anything unrecognised to
        // MAINTENANCE, which is the right answer when reading a row and a catastrophic one when
        // reading a typo out of a command line.
        return null;
    }

    private static String names() {
        return Arrays.stream(SeasonPhase.values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
