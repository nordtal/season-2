package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code /hg}: the three admin commands, declared once.
 *
 * {@code /hg ready} stays a chat command in its own module: it marks the sender ready and is not admin-only.
 */
public final class HungerGamesCommands {

    private HungerGamesCommands() {}

    /**
     * The fewest participants the border arithmetic can divide by.
     *
     * {@code HungerGamesSpec} aliases it, so the config validator and the command refuse the same number.
     */
    public static final int HARD_MINIMUM_PARTICIPANTS = 2;

    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /** {@code /hg start} is also a button in Steward. */
    private static final Set<Surface> CONSOLE_AND_WEB = Set.of(Surface.CONSOLE, Surface.WEB);

    /**
     * {@code /hg start [confirm]}: begins the event, and {@link StartGame} holds its confirmation.
     *
     * An argument, not a path segment: JDA refuses every command when one name is both a subcommand and a group.
     */
    public static final Declaration START = new Declaration(
            List.of("hg", "start"),
            Target.HUNGER_GAMES,
            CONSOLE_AND_WEB,
            true,
            false,
            List.of(eu.nordtal.s2.commands.Argument.choice("confirm", List.of("confirm"))
                    .optional()));

    /** {@code /hg ready-status}: which teams have said they are ready. */
    public static final Declaration READY_STATUS =
            new Declaration(List.of("hg", "ready-status"), Target.HUNGER_GAMES, CONSOLE_ONLY, true, false, List.of());

    /** {@code /hg reload}: the wording and the sounds, never {@code config.yml}. */
    public static final Declaration RELOAD =
            new Declaration(List.of("hg", "reload"), Target.HUNGER_GAMES, CONSOLE_ONLY, true, false, List.of());

    public static List<NordtalCommand<HungerGamesEffects>> all() {
        return List.of(new StartGame(), new ReadyStatus(), new ReloadHungerGames());
    }

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}
