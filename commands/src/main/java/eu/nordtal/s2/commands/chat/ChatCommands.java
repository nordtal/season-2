package eu.nordtal.s2.commands.chat;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;

import java.util.List;
import java.util.Set;

/**
 * The network's own private messaging: {@code /msg}, {@code /whisper} and {@code /r}.
 *
 * <h2>Why the proxy owns them</h2>
 * Because it is the only process that can see both people. Vanilla's {@code /tell} is per-server, so
 * a conversation ends the moment one of the two crosses to another backend - and on this network
 * crossing is normal: every login passes the waiting room, and the hunger games and the SMP are
 * different servers. The proxy also already holds what a line has to be drawn from: the language
 * each side reads, and their admin flag, both out of {@code LoginRoster}.
 *
 * <h2>What the proxy cannot draw, and why that is accepted</h2>
 * The prestige crest and the aura are the SMP's, and the proxy has neither. A private line therefore
 * carries the flag, the name and the admin tag and stops there. Fetching the rest would be a query
 * per message, on the process that must not make one - and the composition it would complete is the
 * chat prefix of a <em>public</em> line, which this deliberately does not look like: a whisper that
 * reads exactly like ordinary chat is a whisper somebody answers in public.
 *
 * <h2>They are not admin commands, which makes them the second such family</h2>
 * {@code /smp status} was the first. {@code CatalogueTest} names every non-admin declaration
 * one by one, so a third arrives as a decision rather than as a default.
 */
public final class ChatCommands {

    /** The recipient, resolved by the adapter against who is connected to the proxy. */
    public static final String PLAYER = "player";

    /** Everything after the name, taken whole. */
    public static final String MESSAGE = "message";

    private ChatCommands() {
    }

    /** {@code /msg <player> <message>}. */
    public static final Declaration MSG = new Declaration(
            List.of("msg"), Target.PROXY, Set.of(Surface.GAME), false, false,
            List.of(Argument.player(PLAYER), Argument.greedy(MESSAGE)));

    /** {@code /whisper <player> <message>} - the same command, under the name half of them type. */
    public static final Declaration WHISPER = new Declaration(
            List.of("whisper"), Target.PROXY, Set.of(Surface.GAME), false, false,
            List.of(Argument.player(PLAYER), Argument.greedy(MESSAGE)));

    /** {@code /r <message>}. */
    public static final Declaration REPLY = new Declaration(
            List.of("r"), Target.PROXY, Set.of(Surface.GAME), false, false,
            List.of(Argument.greedy(MESSAGE)));

    /**
     * Every private-message command.
     *
     * <p>{@link Surface#GAME} only, and that is not an oversight in either direction. The console
     * has no session and therefore no reply partner, and a line from it would arrive signed by
     * nobody; Discord already has private messages of its own.</p>
     */
    public static List<NordtalCommand<ChatEffects>> all() {
        return List.of(new PrivateMessage(MSG), new PrivateMessage(WHISPER), new ReplyMessage());
    }

    /** Every private-message declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}
