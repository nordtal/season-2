package eu.nordtal.s2.commands.announce;

import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;

import java.util.Map;

/** Posts the line, and says in the row whether it went anywhere. */
public final class Announce implements NordtalCommand<AnnounceEffects> {

    @Override
    public Declaration declaration() {
        return AnnounceCommands.ANNOUNCE;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final AnnounceEffects effects) {
        final String language = values.string("language");
        final String text = values.string("text");
        effects.async(() -> {
            // The answer is written into the request row and read by nobody in a hurry; it is
            // there so that "why did the announcement not appear" has a row that says "no
            // channel for de" rather than a DONE row and silence.
            final boolean posted = effects.post(language, text);
            user.reply(posted ? "announce.posted" : "announce.no-channel",
                    Map.of("language", language), posted ? Tone.GOOD : Tone.WARN);
        });
    }
}
