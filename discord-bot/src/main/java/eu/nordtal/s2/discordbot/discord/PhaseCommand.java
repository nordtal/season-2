package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.commands.phase.SetPhase;
import eu.nordtal.s2.commands.phase.SetSeasonDate;
import eu.nordtal.s2.commands.phase.ShowPhase;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.phase.SeasonDates;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * The JDA half of {@code /phase} - the normal way the season phase is switched
 * (docs/season-phases.md#who-may-switch-it). The other way is the same command on the Velocity
 * proxy, kept for when the bot or Discord is down.
 *
 * <h2>This class is an adapter now, and that is the change</h2>
 * It was 480 lines that built the command, checked the flag, confirmed, wrote the row <em>and</em>
 * composed eleven English sentences. The proxy had 346 of its own doing most of the same things
 * differently. Since 2026-09-04 both are adapters over
 * {@code eu.nordtal.s2.commands.phase}: this one registers the command with Discord, resolves who
 * is asking, and offers the button. What the command decides and what it says is shared, in two
 * languages, and asserted by {@code PhaseCommandsTest} without a guild.
 *
 * <p><b>What that cost, stated plainly:</b> the confirmation and the summaries lost their
 * {@code **bold**}. The shared bundle is rendered as MiniMessage on Minecraft and as markdown here,
 * and a string cannot carry both - see the header of {@code messages/commands/en.properties}. The
 * admin-channel lines, which no Minecraft surface ever renders, keep theirs.</p>
 *
 * <h2>Three things this command is still required to do</h2>
 * <ol>
 *   <li><b>Be admin-only</b>, by {@code discord_user.admin} - the flag {@link GuildState} mirrors
 *       from the Discord admin role. Checked twice for a switch: once before the confirmation is
 *       offered and once when it is clicked, because a role can be taken away in between.
 *       {@link DefaultMemberPermissions#DISABLED} on top only hides the command; it is not the
 *       authorisation. {@code show} is checked too, which it was not before 2026-09-04 - the
 *       declaration says the command is admin-only, and one of the two used to disagree.</li>
 *   <li><b>Confirm before switching.</b> {@code PhaseCommands.SET} and {@code SMP_START} carry
 *       {@code irreversible}; {@code LAUNCH} deliberately does not, because setting it again is an
 *       exact undo.</li>
 *   <li><b>Write to the admin channel</b>, like every other access-relevant action. That is
 *       {@link BotPhaseEffects}, not this class.</li>
 * </ol>
 *
 * <h2>The pending decision lives in the button</h2>
 * There is no map of half-finished confirmations. What was chosen is appended to the confirm
 * button's component id, which is the same reasoning as the purchase flow's "the row is the state":
 * nothing to expire, nothing to clean up, and a bot that restarts mid-confirmation has a button that
 * does nothing rather than one that switches the wrong phase.
 */
@Slf4j
public final class PhaseCommand extends ListenerAdapter {

    private final PhaseEffects effects;
    private final Messages messages;
    private final ExecutorService executor;
    private final AdminFlagDao dao;

    private final ShowPhase show = new ShowPhase();
    private final SetPhase set = new SetPhase();
    private final SetSeasonDate launch = SetSeasonDate.launch();
    private final SetSeasonDate smpStart = SetSeasonDate.smpStart();

    public PhaseCommand(final PhaseDirectory phases, final AdminLog admin, final Jdbi jdbi,
                        final Messages messages, final ExecutorService executor) {
        this.effects = new BotPhaseEffects(phases, admin, executor);
        this.messages = messages;
        this.executor = executor;
        this.dao = jdbi.onDemand(AdminFlagDao.class);
    }

    /** What the bot registers with Discord on startup. */
    public static List<CommandData> commands() {
        final OptionData phase = new OptionData(OptionType.STRING, "phase",
                "Which phase to switch to", true);
        // Choices rather than free text: Discord then rejects anything else before the interaction
        // ever reaches the bot, and the names are visible while typing.
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
            case "phase show" -> simple(event, show, Values.none(PhaseCommands.SHOW));
            case "phase launch" -> date(event, launch);
            case "phase smp-start" -> date(event, smpStart);
            // Every other command in the bot comes through this listener too.
            default -> { }
        }
    }

    /** A command with no confirmation: check the flag, run it. */
    private void simple(final SlashCommandInteractionEvent event, final NordtalCommand<PhaseEffects> command,
                        final Values values) {
        event.deferReply(true).queue();
        executor.execute(() -> {
            final DiscordUser user = resolve(event.getUser(), event.getHook());
            if (user == null) {
                return;
            }
            if (!user.admin()) {
                refuse(user);
                return;
            }
            command.run(user, values, effects);
        });
    }

    private void set(final SlashCommandInteractionEvent event) {
        final OptionMapping option = event.getOption("phase");
        final String requested = option == null ? "" : option.getAsString();
        final Optional<SeasonPhase> target = SetPhase.parse(requested);

        event.deferReply(true).queue();
        executor.execute(() -> {
            final DiscordUser user = resolve(event.getUser(), event.getHook());
            if (user == null) {
                return;
            }
            if (!user.admin()) {
                refuse(user);
                return;
            }
            if (target.isEmpty()) {
                // Answered by the command, so that "that is not a phase" is one sentence in one
                // place. Discord's own choices make this unreachable in practice, which is exactly
                // why it must not be a sentence somebody wrote twice.
                set.run(user, new Values(PhaseCommands.SET, Map.of("phase", requested)), effects);
                return;
            }
            offerConfirmation(user, target.get());
        });
    }

    private void offerConfirmation(final DiscordUser user, final SeasonPhase target) {
        final SeasonPhase current;
        try {
            current = effects.phases().currentPhase();
        } catch (final RuntimeException failure) {
            effects.warn("reading the current phase", failure);
            user.reply("phase.failed");
            return;
        }

        user.reply(current == target ? "phase.confirm.same" : "phase.confirm", Map.of(
                "previous", current.name(),
                "current", target.name(),
                "consequence", user.phrase(SetPhase.consequenceKey(target))));

        // Sent after the text, because DiscordUser#reply clears the components on every line: the
        // buttons belong to the finished message and not to a half-written one.
        user.hook().editOriginal(user.text())
                .setComponents(ActionRow.of(
                        Button.danger(Ids.PHASE_CONFIRM + target.name(),
                                messages.get(user.locale(), "command.confirm.yes")),
                        Button.secondary(Ids.PHASE_CANCEL,
                                messages.get(user.locale(), "command.confirm.no"))))
                .queue();
    }

    private void date(final SlashCommandInteractionEvent event, final SetSeasonDate command) {
        final OptionMapping option = event.getOption("when");
        final String typed = option == null ? "" : option.getAsString();
        final Values values = new Values(command.declaration(), Map.of("when", typed));

        event.deferReply(true).queue();
        executor.execute(() -> {
            final DiscordUser user = resolve(event.getUser(), event.getHook());
            if (user == null) {
                return;
            }
            if (!user.admin()) {
                refuse(user);
                return;
            }

            // A typo is answered by the command and never confirmed: nothing was read and nothing
            // will be written, so there is nothing to be sure about.
            final boolean parses = SeasonDates.isClear(typed) || SeasonDates.parse(typed).isPresent();
            if (!parses || !command.declaration().irreversible()) {
                command.run(user, values, effects);
                return;
            }

            // Not command.confirm.retype: that key is the game surface's "type it again", and this
            // surface confirms with a button. What the two share is the flag on the declaration,
            // not the shape of the question.
            user.reply("phase.date.confirm", Map.of(
                    "what", user.phrase(command.whatKey()),
                    "when", typed));
            user.hook().editOriginal(user.text())
                    .setComponents(ActionRow.of(
                            Button.danger(Ids.PHASE_DATE_CONFIRM + typed,
                                    messages.get(user.locale(), "command.confirm.yes")),
                            Button.secondary(Ids.PHASE_CANCEL,
                                    messages.get(user.locale(), "command.confirm.no"))))
                    .queue();
        });
    }

    // ---------------------------------------------------------------- the confirmation

    @Override
    public void onButtonInteraction(final @NotNull ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (Ids.PHASE_CANCEL.equals(id)) {
            event.deferEdit().queue();
            executor.execute(() -> {
                final DiscordUser user = resolve(event.getUser(), event.getHook());
                if (user != null) {
                    user.reply("command.cancelled");
                }
            });
            return;
        }

        if (id != null && id.startsWith(Ids.PHASE_CONFIRM)) {
            confirmed(event, confirmedPhase(id)
                    .map(phase -> new Values(PhaseCommands.SET, Map.of("phase", phase.name())))
                    .orElse(null), set);
            return;
        }
        if (id != null && id.startsWith(Ids.PHASE_DATE_CONFIRM)) {
            confirmed(event, confirmedDate(id)
                    .map(when -> new Values(smpStart.declaration(), Map.of("when", when)))
                    .orElse(null), smpStart);
        }
        // Every other flow's buttons come through here too; only ours carry these prefixes.
    }

    /**
     * The phase a confirm button carries.
     *
     * <p>Package-visible and static so it can be asserted: every flow's buttons arrive at every
     * listener in this bot, and this is the one that switches the season phase. An id minted by an
     * older build and still sitting in a channel is the other case - doing nothing is the only safe
     * reading of it.</p>
     *
     * @param componentId the id of the button that was clicked, may be {@code null}
     * @return the phase, or empty when the button is not one of ours or names a phase this build
     *         does not know
     */
    static Optional<SeasonPhase> confirmedPhase(final String componentId) {
        if (componentId == null || !componentId.startsWith(Ids.PHASE_CONFIRM)) {
            return Optional.empty();
        }
        // Exact, not case-insensitive: this string was minted by this bot, so a name that does not
        // match exactly came from somewhere else.
        final String named = componentId.substring(Ids.PHASE_CONFIRM.length());
        return SetPhase.parse(named).filter(phase -> phase.name().equals(named));
    }

    /**
     * The date a {@code /phase smp-start} confirm button carries.
     *
     * @param componentId the id of the button that was clicked, may be {@code null}
     * @return the date as typed, or empty when the button is not one of ours or carries something
     *         that is no longer a date - an id from an older build, or one that has been edited
     */
    static Optional<String> confirmedDate(final String componentId) {
        if (componentId == null || !componentId.startsWith(Ids.PHASE_DATE_CONFIRM)) {
            return Optional.empty();
        }
        final String when = componentId.substring(Ids.PHASE_DATE_CONFIRM.length());
        if (!SeasonDates.isClear(when) && SeasonDates.parse(when).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(when);
    }

    private void confirmed(final ButtonInteractionEvent event, final Values values,
                           final NordtalCommand<PhaseEffects> command) {
        if (values == null) {
            // A button naming something this build does not know. Nothing to run, and nothing worth
            // telling the clicker beyond that it did not work.
            log.warn("A /phase confirmation button carried an unknown value: {}",
                    event.getComponentId());
            return;
        }

        event.deferEdit().queue();
        executor.execute(() -> {
            final DiscordUser user = resolve(event.getUser(), event.getHook());
            if (user == null) {
                return;
            }
            // Checked again: the confirmation may have been on screen while the role was taken
            // away, and this click is the one that changes something.
            if (!user.admin()) {
                refuse(user);
                return;
            }
            command.run(user, values, effects);
        });
    }

    // ---------------------------------------------------------------- who is asking

    /**
     * Reads the two things about the caller that every branch needs, in one place.
     *
     * @return the caller, or {@code null} when the database could not be reached - in which case
     *         they have already been told
     */
    private DiscordUser resolve(final net.dv8tion.jda.api.entities.User who, final InteractionHook hook) {
        try {
            final boolean admin = AdminFlagDao.admits(dao.isAdmin(who.getId()));
            final Locale locale = Locales.parse(dao.localeOf(who.getId()).orElse(null));
            return new DiscordUser(who, locale, admin, hook, messages);
        } catch (final RuntimeException failure) {
            effects.warn("reading who is asking", failure);
            hook.editOriginal(messages.get(Locale.ENGLISH, "phase.failed"))
                    .setComponents(List.of()).queue();
            return null;
        }
    }

    /**
     * One refusal, in their language.
     *
     * <p>An account the bot has no row for is <b>not</b> an admin, which is why the DAO hands back
     * an empty {@code Optional} rather than folding "unknown" into a boolean itself.</p>
     */
    private static void refuse(final DiscordUser user) {
        user.reply("command.not-admin");
    }
}
