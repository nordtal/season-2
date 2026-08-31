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
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

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

        return new BrigadierCommand(root.then(
                BrigadierCommand.literalArgumentBuilder("set").then(phaseArgument)));
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
        player.sendMessage(Component.text(watch.everRead()
                ? messages.format(locale, "phase.current", "phase", watch.lastKnown().name())
                : messages.format(locale, "phase.current.unread", "phase", watch.lastKnown().name())));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int set(final CommandContext<CommandSource> context) {
        final Player player = (Player) context.getSource();
        final Locale locale = roster.localeOf(player.getUniqueId());
        final String requested = StringArgumentType.getString(context, "phase");

        final SeasonPhase target = parse(requested);
        if (target == null) {
            player.sendMessage(Component.text(messages.format(locale, "phase.unknown",
                    "value", requested, "phases", names())));
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
            player.sendMessage(Component.text(messages.get(locale, "phase.failed")));
            return;
        }

        logger.warn("Season phase switched from the proxy by {} ({}): {} -> {}",
                player.getUsername(), actor, change.previous(), change.current());

        // Do not wait for the poll or the notification to come back around: this process already
        // knows, and refreshing here is what makes the reply and the log agree.
        watch.refresh();

        player.sendMessage(Component.text(change.unchanged()
                ? messages.format(locale, "phase.unchanged", "phase", change.current().name())
                : messages.format(locale, "phase.changed",
                        "previous", change.previous().name(), "current", change.current().name())));
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
