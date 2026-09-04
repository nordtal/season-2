package eu.nordtal.s2.networkcontrol.phase;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.commands.phase.SetPhase;
import eu.nordtal.s2.commands.phase.SetSeasonDate;
import eu.nordtal.s2.commands.phase.ShowPhase;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.phase.SeasonDates;
import eu.nordtal.s2.networkcontrol.command.VelocityUser;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import java.util.Map;
import java.util.Optional;

/**
 * The Brigadier half of {@code /phase} on the proxy - the <b>emergency</b> path for switching the
 * season phase when the bot or Discord is down. The normal path is {@code /phase set} in Discord
 * (docs/season-phases.md#who-may-switch-it).
 *
 * <h2>This class is an adapter now, and that is the change</h2>
 * It was 346 lines of tree-building <em>and</em> decisions until 2026-09-04, and the bot had its own
 * 480 that made some of those decisions differently. What is left here is the three things only
 * Velocity can do: build the tree, decide who the source is, and confirm. Everything the command
 * <em>says</em> and everything it decides lives in {@code eu.nordtal.s2.commands.phase}, once, and
 * is asserted by {@code PhaseCommandsTest} without a proxy.
 *
 * <h2>Decided elsewhere, and not re-opened here</h2>
 * <ul>
 *   <li><b>Brigadier directly</b>, through {@code CommandManager.metaBuilder} - no framework, and
 *       Brigadier is never shaded because Velocity provides it. docs/architecture.md#commands.</li>
 *   <li><b>Authorised by {@code discord_user.admin}</b>, from {@link LoginRoster}, which the login
 *       gate filled and the notification listener keeps current. Not by console and not by a
 *       Velocity permission node: a non-{@link Player} source fails {@code requires} and the command
 *       does not exist for it. Rejected on 2026-08-31 and re-stated in {@code PhaseCommands}.</li>
 *   <li><b>The write goes through {@code PhaseDirectory#switchPhase}</b>, which writes the row, the
 *       {@code audit_log} entry and the {@code NOTIFY} as one statement.</li>
 * </ul>
 *
 * <h2>What it cannot do</h2>
 * If the <em>database</em> is what is down, this command does not work either - the row lives there.
 * docs/season-phases.md says so and names the last resort: an {@code UPDATE} by hand, which the
 * proxy picks up on its next poll. Authorisation still works during an outage, because the admin
 * flag was read at login and is held in memory; it is the write that fails, and it fails loudly.
 *
 * <h2>The confirmation, and what it costs here</h2>
 * {@code /phase set} and {@code /phase smp-start} are declared irreversible, so they are typed
 * twice - inside {@link Confirmations#WINDOW}. On the emergency path that is a deliberate extra
 * second or two, decided by the owner on 2026-09-04 together with "two-step everywhere". The first
 * invocation is also the only place the proxy has ever said <em>what a switch will do to the people
 * who are connected</em>, which the bot's button dialogue has said since it was written.
 */
public final class PhaseCommand {

    private static final String ALIAS = "phase";

    private final LoginRoster roster;
    private final Messages messages;
    private final PhaseEffects effects;
    private final PhaseWatch watch;
    private final Confirmations confirmations;

    private final ShowPhase show = new ShowPhase();
    private final SetPhase set = new SetPhase();
    private final SetSeasonDate launch = SetSeasonDate.launch();
    private final SetSeasonDate smpStart = SetSeasonDate.smpStart();

    public PhaseCommand(final LoginRoster roster, final Messages messages,
                        final PhaseEffects effects, final PhaseWatch watch,
                        final Confirmations confirmations) {
        this.roster = roster;
        this.messages = messages;
        this.effects = effects;
        this.watch = watch;
        this.confirmations = confirmations;
    }

    /** @return the alias, so the caller can build the {@code CommandMeta} without repeating it */
    public static String alias() {
        return ALIAS;
    }

    /** @return the command, ready to hand to {@code CommandManager#register(CommandMeta, Command)} */
    public BrigadierCommand build() {
        // The bare /phase executes `show`. That is a Brigadier tree detail and not a second
        // command: Discord cannot invoke a command that has subcommands on its own, so the shared
        // path is ["phase", "show"] and the proxy adds this as a convenience.
        final LiteralArgumentBuilder<CommandSource> root = BrigadierCommand
                .literalArgumentBuilder(ALIAS)
                .requires(this::mayUse)
                .executes(this::show);

        final RequiredArgumentBuilder<CommandSource, String> phaseArgument = BrigadierCommand
                .requiredArgumentBuilder("phase", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (final SeasonPhase phase : SeasonPhase.values()) {
                        builder.suggest(phase.name());
                    }
                    return builder.buildFuture();
                })
                .executes(this::set);

        return new BrigadierCommand(root
                .then(BrigadierCommand.literalArgumentBuilder("show").executes(this::show))
                .then(BrigadierCommand.literalArgumentBuilder("set").then(phaseArgument))
                .then(BrigadierCommand.literalArgumentBuilder("launch")
                        .then(dateArgument(launch)))
                .then(BrigadierCommand.literalArgumentBuilder("smp-start")
                        .then(dateArgument(smpStart))));
    }

    private RequiredArgumentBuilder<CommandSource, String> dateArgument(final SetSeasonDate command) {
        // greedyString, not word: a date carries a space, and Brigadier would otherwise hand over
        // "2026-10-01" and call "18:00" an unexpected second argument.
        return BrigadierCommand.requiredArgumentBuilder("when", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    builder.suggest(SeasonDates.CLEAR);
                    return builder.buildFuture();
                })
                .executes(context -> date(context, command));
    }

    // ---------------------------------------------------------------- authorisation

    /**
     * The whole authorisation rule: a connected player whose login query found
     * {@code discord_user.admin} set.
     *
     * <p>A non-{@link Player} source - the console - fails here and the command does not exist for
     * it, which is the rejection docs/season-phases.md records rather than an oversight. The lookup
     * is a map read because Brigadier evaluates this while building the tree it sends to a
     * client.</p>
     */
    private boolean mayUse(final CommandSource source) {
        return source instanceof Player player && roster.isAdmin(player.getUniqueId());
    }

    private NordtalUser userOf(final CommandContext<CommandSource> context) {
        return new VelocityUser((Player) context.getSource(), roster, messages);
    }

    // ---------------------------------------------------------------- the four branches

    private int show(final CommandContext<CommandSource> context) {
        show.run(userOf(context), Values.none(PhaseCommands.SHOW), effects);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int set(final CommandContext<CommandSource> context) {
        final NordtalUser user = userOf(context);
        final String requested = StringArgumentType.getString(context, "phase");
        final Values values = new Values(PhaseCommands.SET, Map.of("phase", requested));

        // An unknown name is answered by the command, not here: there is one place that decides
        // what "that is not a phase" says, and asking somebody to confirm a phase that does not
        // exist would be worse than useless.
        final Optional<SeasonPhase> target = SetPhase.parse(requested);
        if (target.isEmpty()) {
            return run(set, user, values);
        }

        // Normalised, so that "/phase set smp" confirms "/phase set SMP" - they are the same
        // command, and a confirmation that depended on capitalisation would be a confirmation that
        // silently never arrives.
        final String typed = "/phase set " + target.get().name();
        if (!confirmations.confirm(user, typed)) {
            final SeasonPhase current = watch.lastKnown();
            user.reply(current == target.get() ? "phase.confirm.same" : "phase.confirm", Map.of(
                    "previous", current.name(),
                    "current", target.get().name(),
                    "consequence", user.phrase(SetPhase.consequenceKey(target.get()))));
            retype(user, typed);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }
        return run(set, user, values);
    }

    private int date(final CommandContext<CommandSource> context, final SetSeasonDate command) {
        final NordtalUser user = userOf(context);
        final String when = StringArgumentType.getString(context, "when");
        final Values values = new Values(command.declaration(), Map.of("when", when));

        // Same shape as an unknown phase: the command owns the sentence, and a typo must not have
        // to be typed twice before it is called a typo. SeasonDates is :common's parser, so asking
        // it here duplicates no decision.
        if (!SeasonDates.isClear(when) && SeasonDates.parse(when).isEmpty()) {
            return run(command, user, values);
        }

        if (command.declaration().irreversible()) {
            final String typed = command.declaration().name() + " " + when;
            if (!confirmations.confirm(user, typed)) {
                retype(user, typed);
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }
        }
        return run(command, user, values);
    }

    private void retype(final NordtalUser user, final String typed) {
        user.reply("command.confirm.retype", Map.of(
                "command", typed,
                "seconds", String.valueOf(confirmations.window().toSeconds())));
    }

    private int run(final NordtalCommand<PhaseEffects> command, final NordtalUser user,
                    final Values values) {
        command.run(user, values, effects);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }
}
