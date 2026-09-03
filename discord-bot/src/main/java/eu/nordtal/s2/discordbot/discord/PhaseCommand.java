package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.phase.SeasonDateRefused;
import eu.nordtal.s2.common.phase.SeasonDates;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IDeferrableCallback;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * {@code /phase set <phase>} - the normal way the season phase is switched
 * ({@code docs/season-phases.md#who-may-switch-it}). The other way is a command on the Velocity
 * proxy, kept for when the bot or Discord is down.
 *
 * <h2>Three things this command is required to do</h2>
 * <ol>
 *   <li><b>Be admin-only</b>, and specifically by {@code discord_user.admin} - the flag
 *       {@link GuildState} mirrors from the Discord admin role. It is checked twice: once before
 *       the confirmation is offered and once again when it is clicked, because a role can be taken
 *       away in between and the second check costs one indexed lookup.
 *       {@link DefaultMemberPermissions#DISABLED} on top of that is what the other admin commands
 *       do, and it only hides the command - it is not the authorisation.</li>
 *   <li><b>Confirm before switching.</b> The phase decides who may join and where they land; a
 *       switch to {@code SMP} disconnects every player online without active access. The
 *       confirmation names the phase being left, the phase being entered and what will happen to
 *       the people currently connected.</li>
 *   <li><b>Write to the admin channel</b>, like every other access-relevant action.</li>
 * </ol>
 *
 * <h2>What it must not do</h2>
 * It does not write the phase and it does not write the {@code audit_log} row.
 * {@link PhaseDirectory#switchPhase} does both in one SQL statement, so there is no way to switch
 * the phase without the audit entry - see {@link AuditDao}, which is no longer the only writer of
 * that table. Calling {@link AdminLog#record} here as well would file the same switch twice.
 *
 * <h2>The chosen phase lives in the button</h2>
 * There is no map of half-finished confirmations. The phase is appended to the confirm button's
 * component id ({@link Ids#PHASE_CONFIRM}), which is the same reasoning as the purchase flow's
 * "the row is the state": nothing to expire, nothing to clean up, and a bot that restarts
 * mid-confirmation simply has a button that does nothing rather than one that switches the wrong
 * phase.
 */
@Slf4j
public final class PhaseCommand extends ListenerAdapter {

    /**
     * What {@code audit_log.detail} says about a switch made here.
     * <p>
     * Fixed rather than typed by the admin: the free text would have to survive the trip from the
     * command to the confirmation click, and the only place this flow keeps state across that trip
     * is a component id with 100 characters in it.
     * </p>
     */
    private static final String REASON = "/phase set in Discord";

    private static final String NOT_AN_ADMIN =
            "You are not an admin. The season phase is switched by whoever holds the admin role in "
                    + "this guild, and nothing else - see docs/season-phases.md.";

    private final PhaseDirectory phases;
    private final AdminLog admin;
    private final ExecutorService executor;
    private final AdminFlagDao dao;

    public PhaseCommand(final PhaseDirectory phases, final AdminLog admin, final Jdbi jdbi,
                        final ExecutorService executor) {
        this.phases = phases;
        this.admin = admin;
        this.executor = executor;
        this.dao = jdbi.onDemand(AdminFlagDao.class);
    }

    /** What the bot registers with Discord on startup. */
    public static List<CommandData> commands() {
        final OptionData phase = new OptionData(OptionType.STRING, "phase",
                "Which phase to switch to", true);
        // Choices rather than free text: Discord then rejects anything else before the interaction
        // ever reaches the bot, and the four names are visible while typing.
        for (final SeasonPhase value : SeasonPhase.values()) {
            phase.addChoice(value.name(), value.name());
        }

        return List.of(Commands.slash("phase", "The season phase: who may join and where they land.")
                .addSubcommands(
                        new SubcommandData("set", "Switch the season phase.").addOptions(phase),
                        new SubcommandData("show",
                                "Show the phase and the two dates the season is measured against."),
                        new SubcommandData("launch", "When the network opens.")
                                .addOptions(dateOption()),
                        new SubcommandData("smp-start", "When paid access starts running.")
                                .addOptions(dateOption()))
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED));
    }

    /**
     * A fresh option per subcommand: JDA's builders are mutable, and handing the same instance to
     * two subcommands makes them one option that two commands share.
     */
    private static OptionData dateOption() {
        return new OptionData(OptionType.STRING, "when",
                SeasonDates.PATTERN + " in " + SeasonDates.ZONE.getId()
                        + " time, or '" + SeasonDates.CLEAR + "'", true);
    }

    // ---------------------------------------------------------------- the command

    @Override
    public void onSlashCommandInteraction(final @NotNull SlashCommandInteractionEvent event) {
        switch (event.getFullCommandName()) {
            case "phase set" -> set(event);
            case "phase show" -> show(event);
            case "phase launch" -> date(event, Column.LAUNCH);
            case "phase smp-start" -> date(event, Column.SMP_START);
            // Every other command in the bot comes through this listener too.
            default -> { }
        }
    }

    private void set(final SlashCommandInteractionEvent event) {
        final OptionMapping option = event.getOption("phase");
        final Optional<SeasonPhase> target = phaseOf(option == null ? null : option.getAsString());
        if (target.isEmpty()) {
            event.reply("That is not a season phase. The five are: " + String.join(", ", names()) + ".")
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                if (!maySwitch(dao.isAdmin(event.getUser().getId()))) {
                    event.getHook().editOriginal(NOT_AN_ADMIN).queue();
                    return;
                }
                offerConfirmation(event, target.get());
            } catch (final RuntimeException exception) {
                fail(event, "reading the current phase", exception);
            }
        });
    }

    private void offerConfirmation(final SlashCommandInteractionEvent event, final SeasonPhase target) {
        final SeasonPhase current = phases.currentPhase();
        event.getHook().editOriginal(confirmation(current, target))
                .setComponents(ActionRow.of(
                        Button.danger(Ids.PHASE_CONFIRM + target.name(), "Switch to " + target),
                        Button.secondary(Ids.PHASE_CANCEL, "Cancel")))
                .queue();
    }

    // ---------------------------------------------------------------- the two dates

    /** Which column a {@code /phase launch} or {@code /phase smp-start} is about. */
    enum Column {

        LAUNCH("the network opens", "SET_LAUNCH"),
        SMP_START("paid access starts running", "SET_SMP_START");

        private final String what;
        private final String action;

        Column(final String what, final String action) {
            this.what = what;
            this.action = action;
        }
    }

    /**
     * {@code /phase show} - the phase and both dates in one place.
     * <p>
     * Discord cannot invoke a command that has subcommands on its own, so this is a subcommand
     * rather than the bare {@code /phase} the proxy answers. It is the only one of the four that
     * asks nothing of the admin flag: reading the dates changes nothing, and an admin who cannot
     * see them is an admin who guesses.
     * </p>
     */
    private void show(final SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                event.getHook().editOriginal(overview(phases.currentPhase(),
                        phases.launch().orElse(null), phases.smpStart().orElse(null))).queue();
            } catch (final RuntimeException exception) {
                fail(event, "reading the season dates", exception);
            }
        });
    }

    private void date(final SlashCommandInteractionEvent event, final Column column) {
        final OptionMapping option = event.getOption("when");
        final String typed = option == null ? "" : option.getAsString();

        final Instant at;
        if (SeasonDates.isClear(typed)) {
            at = null;
        } else {
            final var parsed = SeasonDates.parse(typed);
            if (parsed.isEmpty()) {
                // Answered before deferring: nothing was read and nothing will be written, so the
                // admin gets the pattern back immediately rather than after a round trip.
                event.reply(notADate()).setEphemeral(true).queue();
                return;
            }
            at = parsed.get();
        }

        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                if (!maySwitch(dao.isAdmin(event.getUser().getId()))) {
                    event.getHook().editOriginal(NOT_AN_ADMIN).queue();
                    return;
                }
                final String actor = event.getUser().getId();
                final DateChange change = column == Column.LAUNCH
                        ? phases.setLaunch(at, actor)
                        : phases.setSmpStart(at, actor);

                admin.note(event.getUser().getAsMention() + " set when " + column.what + " to **"
                        + SeasonDates.format(change.current()) + "**"
                        + (change.movedAccess()
                           ? ", moving " + change.grants() + " access period(s) across "
                                   + change.accounts() + " account(s) with it."
                           : "."));
                event.getHook().editOriginal(summary(column, change)).queue();
            } catch (final SeasonDateRefused refused) {
                // Not a failure: the admin asked for something the model does not allow, and the
                // message is written for them. Nothing was written, so nothing is reported.
                event.getHook().editOriginal(refused.getMessage()).queue();
            } catch (final RuntimeException exception) {
                fail(event, "setting when " + column.what, exception);
            }
        });
    }

    // ---------------------------------------------------------------- the confirmation

    @Override
    public void onButtonInteraction(final @NotNull ButtonInteractionEvent event) {
        if (Ids.PHASE_CANCEL.equals(event.getComponentId())) {
            event.editMessage("Cancelled. The season phase is unchanged.")
                    .setComponents(List.of()).queue();
            return;
        }
        final Optional<SeasonPhase> target = confirmedPhase(event.getComponentId());
        if (target.isEmpty()) {
            // Every other flow's buttons come through here too; only ours carry a phase.
            return;
        }

        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                // Checked again: the confirmation may have been sitting on screen while the role
                // was taken away, and this click is the one that changes something.
                if (!maySwitch(dao.isAdmin(event.getUser().getId()))) {
                    event.getHook().editOriginal(NOT_AN_ADMIN).setComponents(List.of()).queue();
                    return;
                }
                switchPhase(event, target.get());
            } catch (final RuntimeException exception) {
                fail(event, "switching the phase to " + target.get(), exception);
            }
        });
    }

    private void switchPhase(final ButtonInteractionEvent event, final SeasonPhase target) {
        // This one call writes the row, the audit entry and the NOTIFY. Nothing here writes any of
        // the three itself, and admin.record is deliberately not called.
        final PhaseChange change = phases.switchPhase(target, event.getUser().getId(), REASON);

        if (change.unchanged()) {
            admin.note(event.getUser().getAsMention() + " set the season phase to **"
                    + change.current() + "**, which it already was.");
        } else {
            admin.note(event.getUser().getAsMention() + " switched the season phase from **"
                    + change.previous() + "** to **" + change.current() + "**. "
                    + consequence(change.current()));
        }

        event.getHook().editOriginal("The season phase is now **" + change.current() + "**.")
                .setComponents(List.of())
                .queue();
    }

    // ---------------------------------------------------------------- decisions, kept testable

    /**
     * Parses the phase somebody chose.
     * <p>
     * Deliberately <b>not</b> {@link SeasonPhase#fromDatabase(String)}, which answers
     * {@link SeasonPhase#MAINTENANCE} to anything it does not recognise. That is the right answer
     * for a value read out of the database - an unreadable phase must never be more permissive than
     * the real one - and the worst possible answer for a value that arrived from outside: a name
     * this build does not know would silently lock the whole network out.
     * </p>
     *
     * @param value the option value, may be {@code null}
     * @return the phase, or empty if it is not one of the four
     */
    static Optional<SeasonPhase> phaseOf(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase.name().equals(value)) {
                return Optional.of(phase);
            }
        }
        return Optional.empty();
    }

    /**
     * The authorisation rule, in one place.
     *
     * @param adminFlag what {@link AdminFlagDao#isAdmin(String)} answered
     * @return whether this account may switch the phase - an account the bot has no row for is
     *         <b>not</b> an admin, which is why the DAO hands back an empty Optional rather than
     *         folding "unknown" into a boolean itself
     */
    static boolean maySwitch(final Optional<Boolean> adminFlag) {
        return adminFlag.orElse(false);
    }

    /**
     * The phase a confirm button carries.
     *
     * @param componentId the id of the button that was clicked, may be {@code null}
     * @return the phase to switch to, or empty when the button is not one of ours or names a phase
     *         this build does not know
     */
    static Optional<SeasonPhase> confirmedPhase(final String componentId) {
        if (componentId == null || !componentId.startsWith(Ids.PHASE_CONFIRM)) {
            return Optional.empty();
        }
        return phaseOf(componentId.substring(Ids.PHASE_CONFIRM.length()));
    }

    /**
     * What the admin is asked to confirm.
     * <p>
     * It names both phases and what happens to the people who are connected right now, because
     * that is the part a phase switch gets wrong: {@code SMP} is not "the season starts", it is
     * "everyone who has not paid is disconnected".
     * </p>
     *
     * @param current the phase the network is in
     * @param target  the phase that was asked for
     * @return the confirmation text
     */
    static String confirmation(final SeasonPhase current, final SeasonPhase target) {
        final StringBuilder text = new StringBuilder("Switch the season phase from **")
                .append(current).append("** to **").append(target).append("**?\n\n");
        if (current == target) {
            text.append("The network is already in that phase. Switching writes the row and the "
                    + "audit entry again and changes nothing else.\n\n");
        }
        return text.append(consequence(target)).toString();
    }

    /** What a switch to {@code target} does to the players who are online, per docs/season-phases.md. */
    private static String consequence(final SeasonPhase target) {
        return switch (target) {
            case SMP -> "Everyone online is moved to `smp`, and **every player without active "
                    + "access is disconnected** with the message the login gate uses.";
            case MAINTENANCE -> "**Only admins get in.** Everyone else online is held in `limbo` "
                    + "or refused.";
            case PRE_EVENT, START_EVENT -> "Everyone online is moved to `hunger-games`. Access is "
                    + "not required in this phase - a linked, non-banned member is enough.";
            case PRE_LAUNCH -> "**The network closes.** Only admins get in; everybody else online "
                    + "is disconnected and sees the countdown to `season_phase.launch`. Switching "
                    + "here is going back before the opening, not pausing - use `MAINTENANCE` for a "
                    + "pause.";
        };
    }

    private static List<String> names() {
        return Arrays.stream(SeasonPhase.values()).map(Enum::name).toList();
    }

    /**
     * One place for "the database said no".
     * <p>
     * The admin gets a plain sentence, the admin channel gets the detail. A phase switch that
     * failed halfway is exactly the thing nobody may find out about from a log file.
     * </p>
     */
    /** What an admin sees when {@code when} was neither a date nor {@code clear}. */
    static String notADate() {
        return "That is not a date. Type it as `" + SeasonDates.PATTERN + "` in "
                + SeasonDates.ZONE.getId() + " time - for example `2026-10-01 18:00` - or `"
                + SeasonDates.CLEAR + "` to take the date away again.";
    }

    /** {@code /phase show}, kept out of the interaction so it can be asserted. */
    static String overview(final SeasonPhase phase, final Instant launch, final Instant smpStart) {
        return "The season phase is **" + phase + "**.\n"
                + "\u2022 The network opens: **" + SeasonDates.format(launch) + "**\n"
                + "\u2022 Paid access starts running: **" + SeasonDates.format(smpStart) + "**\n\n"
                + "Both are set by hand and neither switches the phase when it passes - that stays "
                + "`/phase set`. Times are " + SeasonDates.ZONE.getId() + ".";
    }

    /**
     * What one date write is reported as.
     * <p>
     * The moved-access sentence is not decoration: {@code smp_start} rewrites rows belonging to
     * people who are not in the room, and the number is the only place an admin finds out that it
     * did. Clearing says so explicitly for the same reason - "nothing moved" is a different fact
     * from "nothing was there to move".
     * </p>
     */
    static String summary(final Column column, final DateChange change) {
        final StringBuilder text = new StringBuilder();
        if (change.current() == null) {
            text.append("There is no date for when ").append(column.what)
                    .append(" any more. Access bought from now on starts immediately, and the bot "
                            + "warns on every such purchase.");
            if (column == Column.SMP_START) {
                text.append("\n\nThe access already sold **did not move** - there is no date left "
                        + "to anchor it to, so everybody keeps the window they have.");
            }
            return text.toString();
        }

        text.append("**").append(capitalise(column.what)).append("** on **")
                .append(SeasonDates.format(change.current())).append("**");
        if (change.unchanged()) {
            text.append(", which is what it already said.");
            return text.toString();
        }
        text.append(" (was ").append(SeasonDates.format(change.previous())).append(").");

        if (column == Column.SMP_START) {
            text.append(change.movedAccess()
                        ? "\n\nMoved **" + change.grants() + "** access period(s) belonging to **"
                                + change.accounts() + "** account(s) to match. Stacked periods stay "
                                + "stacked and nobody's length changed."
                        : "\n\nNo access had to move.");
        }
        return text.toString();
    }

    private static String capitalise(final String what) {
        return Character.toUpperCase(what.charAt(0)) + what.substring(1);
    }

    private void fail(final IDeferrableCallback event, final String what,
                      final RuntimeException exception) {
        log.error("A phase switch failed while {}", what, exception);
        admin.alert("A phase switch failed while " + what + ": `" + exception + "`");
        event.getHook().editOriginal("That did not work. The season phase has not been changed; "
                        + "the admin channel has the detail.")
                .setComponents(List.of())
                .queue();
    }
}
