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
 * <p>Sent by a server, never typed: the SMP renders a milestone's completion or a farm-reset
 * warning in each language it has a bundle for, and submits one row per language through the
 * {@code command_request} transport. The bot's inbox runs it and posts. This is the shape
 * {@code docs/state-of-play.md} finding 52 asked for - "the Discord half of a milestone
 * announcement is the same missing wire" - and it is the same wire as every other command: the
 * one thing the transport lacked was a surface nobody types on.</p>
 *
 * <p>The text arrives rendered because the names it carries (a milestone's, in the sender's
 * bundle) live in the sender, and a bot that had to know them would be a bot with a copy of
 * {@code milestones.yml}.</p>
 */
public final class AnnounceCommands {

    private AnnounceCommands() {
    }

    public static final Declaration ANNOUNCE = new Declaration(
            List.of("announce"), Target.BOT, Set.of(Surface.SYSTEM), false, false,
            List.of(Argument.word("language"), Argument.greedy("text")));

    public static List<NordtalCommand<AnnounceEffects>> all() {
        return List.of(new Announce());
    }

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}
