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
 * <p>Sent by a server, never typed: the SMP renders a milestone completion or a farm-reset warning
 * in each language it has a bundle for and submits one row per language through the
 * {@code command_request} transport; the bot's inbox runs it and posts.</p>
 *
 * <p>The text arrives already rendered, because the names it carries live in the sender's bundle -
 * a bot that had to know them would need a copy of {@code milestones.yml}.</p>
 */
public final class AnnounceCommands {

    private AnnounceCommands() {
    }

    public static final Declaration ANNOUNCE = new Declaration(
            // SYSTEM because the SMP writes these rows by itself at a milestone, WEB because an
            // admin can also write one by hand - the same command, two askers, one implementation.
            //
            // adminOnly, and it costs the SMP nothing: AdminCheck lets `source = 'CONSOLE'` through
            // by definition, and that is what Announcer#row writes. What it adds is the second
            // check, at the moment the inbox runs the row rather than at the moment the browser
            // submitted it - so a WEB row queued by somebody whose admin role has since been taken
            // away does not still post into an announcement channel.
            List.of("announce"), Target.BOT, Set.of(Surface.SYSTEM, Surface.WEB), true, false,
            List.of(Argument.word("language"), Argument.greedy("text")));

    public static List<NordtalCommand<AnnounceEffects>> all() {
        return List.of(new Announce());
    }

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}
