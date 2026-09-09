package eu.nordtal.s2.commands.info;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;

import java.util.List;
import java.util.Set;

/**
 * The two commands that only print something: {@code /discord} and {@code /rules}.
 *
 * <h2>Why the proxy owns them</h2>
 * Because they have to work in the waiting room. A player held there is precisely the player who
 * needs to be told where the Discord is, and a backend command cannot answer somebody who is not on
 * a backend. Being on the proxy also makes them one copy of one text rather than three.
 *
 * <h2>They are not admin commands, which makes them the third such family</h2>
 * {@code /smp status} was the first and the private messages the second. {@code CatalogueTest} names
 * every non-admin declaration one by one, so a fourth arrives as a decision rather than as a
 * default.
 */
public final class InfoCommands {

    /**
     * The key {@code /discord} prints, in the proxy's own bundle rather than the shared one.
     *
     * <p>A constant so that the command, the adapter and the test that asserts the key exists all
     * name the same string - see {@link InfoEffects} for why the value cannot live in this
     * module.</p>
     */
    public static final String DISCORD_TEXT = "info.discord";

    /** The key {@code /rules} prints. Ships as a marked placeholder - see {@link ShowRules}. */
    public static final String RULES_TEXT = "info.rules";

    private InfoCommands() {
    }

    /** {@code /discord}. */
    public static final Declaration DISCORD = new Declaration(
            List.of("discord"), Target.PROXY, Set.of(Surface.GAME), false, false, List.of());

    /** {@code /rules}. */
    public static final Declaration RULES = new Declaration(
            List.of("rules"), Target.PROXY, Set.of(Surface.GAME), false, false, List.of());

    /** Both of them. */
    public static List<NordtalCommand<InfoEffects>> all() {
        return List.of(new ShowDiscord(), new ShowRules());
    }

    /** Both declarations. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}
