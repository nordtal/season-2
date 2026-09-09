package eu.nordtal.s2.commands.chat;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;
import java.util.Objects;

/**
 * {@code /msg <player> <message>} - and {@code /whisper}, which is the same command under a second
 * name.
 *
 * <h2>Why this replaces vanilla's</h2>
 * {@code /tell}, {@code /msg}, {@code /w} and {@code /teammsg} are per-server: two people in the
 * same conversation who are not on the same backend cannot use them, and on this network that is the
 * ordinary case rather than an edge one - the hunger games and the SMP are different servers and
 * everybody crosses the waiting room. This one lives on the proxy, so it works wherever both of them
 * are, and it renders each side in that side's own language.
 *
 * <h2>Two declarations rather than an alias</h2>
 * {@link Declaration} has no alias, deliberately: a command with two names is a command people
 * report bugs about twice, and every adapter would need to know which name is the real one. Two
 * declarations sharing one class costs a second sentence in the bundle and buys a surface where
 * neither name is a special case.
 */
public final class PrivateMessage implements NordtalCommand<ChatEffects> {

    private final Declaration declaration;

    /** @param declaration {@link ChatCommands#MSG} or {@link ChatCommands#WHISPER} */
    public PrivateMessage(final Declaration declaration) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
    }

    @Override
    public Declaration declaration() {
        return declaration;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final ChatEffects effects) {
        // The recipient is a UUID by the time it is here: the adapter resolved the name against who
        // is connected and answered command.player-offline itself if nobody was. What is left is
        // the window between that resolution and this line, which GONE covers.
        final java.util.UUID to = values.player(ChatCommands.PLAYER);
        final String text = values.string(ChatCommands.MESSAGE);
        effects.async(() -> {
            final ChatEffects.Outcome outcome;
            try {
                outcome = effects.whisper(user, to, text);
            } catch (final RuntimeException failure) {
                // Never with the text in it. A failure here is worth a log line and the message is
                // not ours to keep.
                effects.warn(declaration.name() + " could not be delivered", failure);
                user.reply("chat.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            if (outcome != ChatEffects.Outcome.SENT) {
                user.reply("command.player-offline", Map.of(), Feedback.REFUSED);
            }
        });
    }
}
