package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code /hg} - the three admin commands, declared once.
 *
 * These three exist on both platforms and every backend, unlike {@code /hg ready}: it marks the
 * <em>sender</em> ready, and neither the console nor a Discord member is registered for a game. It
 * also is not admin-only, which every command in this catalogue is, so it stays a chat command in
 * its own module, registered next to these.
 *
 * {@code /hg start} keeps its own two-step rather than the generic "type it again" the catalogue's
 * {@link Declaration#irreversible()} produces, because the difference is what it says: the generic
 * sentence is "this cannot be undone", the one this command has is "only 4 participants are
 * registered, below the recommended 8". A warning carrying the number is worth more than a warning
 * carrying none, and doubling them would train an admin to type everything twice - which is how the
 * guard on the command that deletes a world stops being read.
 */
public final class HungerGamesCommands {

    private HungerGamesCommands() {}

    /**
     * The floor the border arithmetic needs, and the one number here that is not configuration.
     *
     * In this module rather than on the config interface, because {@code HungerGamesSpec} is a
     * Paper module compiled against a platform, so the command that enforces it could not see a
     * constant declared there. {@code BorderMath} divides by the participant count and floors at
     * one; below two there is no game to shrink a border around. {@code HungerGamesSpec} aliases
     * this one, so the config validator and the command refuse the same number by construction.
     */
    public static final int HARD_MINIMUM_PARTICIPANTS = 2;

    /**
     * Console only: none of the three {@code /hg} commands is reachable from {@link Surface#GAME}
     * or {@link Surface#DISCORD}, {@code /hg ready-status} (which only reads) included.
     */
    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /** {@code /hg start} is also a button in Steward - see {@code SmpCommands.CONSOLE_AND_WEB}. */
    private static final Set<Surface> CONSOLE_AND_WEB = Set.of(Surface.CONSOLE, Surface.WEB);

    /**
     * {@code /hg start [confirm]} - begins the event.
     *
     * Its own confirmation lives in {@link StartGame}.
     *
     * {@code confirm} is an argument and not a second path: {@code ["hg","start","confirm"]} reads
     * better and does not work, because Discord builds a two-segment path as a <b>subcommand</b> and
     * a three-segment one as a <b>subcommand group</b>, and a command may not have both under the
     * same name. JDA validates the whole set at registration and refuses <em>all of it</em> - so the
     * guild would lose every command it has, with one exception at startup as the only symptom.
     *
     * As an optional choice argument the chat syntax is unchanged - {@code /hg start confirm} is
     * still exactly what a player types, because Brigadier reads a trailing optional word - and
     * Discord gets an optional dropdown with one value in it. {@code DiscordCommandsTest} now
     * asserts no path is both a subcommand and a group, so the next one fails the build.
     */
    public static final Declaration START = new Declaration(
            List.of("hg", "start"),
            Target.HUNGER_GAMES,
            CONSOLE_AND_WEB,
            true,
            false,
            List.of(eu.nordtal.s2.commands.Argument.choice("confirm", List.of("confirm"))
                    .optional()));

    /** {@code /hg ready-status} - which teams have said they are ready. */
    public static final Declaration READY_STATUS =
            new Declaration(List.of("hg", "ready-status"), Target.HUNGER_GAMES, CONSOLE_ONLY, true, false, List.of());

    /** {@code /hg reload} - the wording and the sounds, never {@code config.yml}. */
    public static final Declaration RELOAD =
            new Declaration(List.of("hg", "reload"), Target.HUNGER_GAMES, CONSOLE_ONLY, true, false, List.of());

    /**
     * Every {@code /hg} command.
     *
     * {@link StartGame} holds the confirmation window, and one instance is what makes a warning
     * shown by {@code /hg start} spendable by {@code /hg start confirm} - they are one command with
     * an optional argument, so that falls out rather than having to be arranged.
     */
    public static List<NordtalCommand<HungerGamesEffects>> all() {
        return List.of(new StartGame(), new ReadyStatus(), new ReloadHungerGames());
    }

    /** Every {@code /hg} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}
