package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;

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
                .addSubcommands(new SubcommandData("set", "Switch the season phase.")
                        .addOptions(phase))
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED));
    }

    // ---------------------------------------------------------------- the command

    @Override
    public void onSlashCommandInteraction(final @NotNull SlashCommandInteractionEvent event) {
        if (!"phase set".equals(event.getFullCommandName())) {
            return;
        }
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
