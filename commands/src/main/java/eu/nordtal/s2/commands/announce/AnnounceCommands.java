package eu.nordtal.s2.commands.announce;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code announce <language> <text>}: one line into one language's Discord announcement channel.
 *
 * Sent by the SMP per language, never typed, with the text already rendered.
 */
public final class AnnounceCommands {

    private AnnounceCommands() {}

    public static final Declaration ANNOUNCE = new Declaration(
            // SYSTEM because the SMP writes these rows by itself at a milestone.
            List.of("announce"),
            Target.BOT,
            Set.of(Surface.SYSTEM, Surface.WEB),
            true,
            false,
            List.of(Argument.word("language"), Argument.greedy("text")));

    public static List<NordtalCommand<AnnounceEffects>> all() {
        return List.of(new Announce());
    }

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}
