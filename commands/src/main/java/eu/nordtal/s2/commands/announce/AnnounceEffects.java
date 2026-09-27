package eu.nordtal.s2.commands.announce;

import eu.nordtal.s2.commands.CommandEffects;

/** What the Discord bot does with an announcement: post it, in one language's channel. */
public interface AnnounceEffects extends CommandEffects {

    /**
     * Posts a line into one language's announcement channel.
     *
     * @param languageTag the language, as in {@code access.yml#languages[].tag}
     * @param text        the line, already rendered in that language, plain text
     * @return whether it was posted; {@code false} when no channel is configured for that language
     */
    boolean post(String languageTag, String text);
}
