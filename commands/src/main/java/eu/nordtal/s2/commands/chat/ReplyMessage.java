package eu.nordtal.s2.commands.chat;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

import java.util.Map;

/**
 * {@code /r <message>} - back to whoever this session last exchanged a private message with.
 *
 * <h2>Why "this session" and not a column</h2>
 * The partner is held in the proxy's memory and dies with the process (Till, 2026-09-08). Writing it
 * down would mean a table of who talks to whom, which is a record of exactly the thing this whole
 * feature is built not to keep - and the cost of not writing it is that a proxy restart makes
 * everybody's next {@code /r} say there is nobody to reply to.
 *
 * <h2>The partner is set by both sides of a message</h2>
 * Sending sets it and receiving sets it, so a reply to somebody who just wrote to you works without
 * either of you having typed their name. That is the whole of what people expect from {@code /r},
 * and it is the reason it is a separate command rather than a shortcut for {@code /msg}.
 */
public final class ReplyMessage implements NordtalCommand<ChatEffects> {

    @Override
    public Declaration declaration() {
        return ChatCommands.REPLY;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final ChatEffects effects) {
        final String text = values.string(ChatCommands.MESSAGE);
        effects.async(() -> {
            final ChatEffects.Outcome outcome;
            try {
                outcome = effects.replyToLast(user, text);
            } catch (final RuntimeException failure) {
                effects.warn("/r could not be delivered", failure);
                user.reply("chat.failed", Map.of(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            switch (outcome) {
                case SENT -> { }
                // Two different sentences, because they are two different situations and the player
                // can act on the difference: "nobody has written to you" means type their name,
                // "they have gone" means they were there a moment ago.
                case NO_PARTNER -> user.reply("chat.no-partner", Map.of(), Feedback.REFUSED, Tone.WARN);
                case GONE -> user.reply("command.player-offline", Map.of(), Feedback.REFUSED, Tone.WARN);
            }
        });
    }
}
