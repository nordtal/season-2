package eu.nordtal.s2.discordbot.discord;

import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/**
 * The one way this bot draws an embed.
 *
 * <h2>An embed is UI, not a paragraph in a box</h2>
 * A title, one-word headings, and values that are data: {@code smp 0.9.3 → 0.9.4} rather than a
 * sentence saying the plugin was updated. What happened is the colour, when is the timestamp, and
 * who asked is a field. Every embed the bot sends goes through here so the next one cannot drift
 * back into prose.
 *
 * <h2>Discord's limits are answered here, once</h2>
 * 25 fields, 1024 characters per field, 4096 in the description and 6000 across the whole embed.
 * JDA refuses an embed over any of them, and the person watching then sees nothing at all.
 * {@link #block} is the answer for anything that grows with the network: its lines are packed into
 * as few fields as they fit, and what does not fit is <em>counted</em> ("+3 more") rather than cut
 * off mid-line. There is no fallback to a code block. A code block is for something to copy.
 */
public final class Card {

    static final int FIELDS = 25;
    static final int FIELD_VALUE = 1024;
    static final int FIELD_NAME = 256;
    static final int TITLE = 256;
    static final int DESCRIPTION = 4096;
    static final int TOTAL = 6000;

    /** Discord drops a field with an empty name; this is the name of a block's continuation. */
    private static final String CONTINUED = "​";

    /** The colour of an embed is its outcome. */
    public enum Accent {
        /** nordtal blue: the bot's own standing messages. */
        NORDTAL(0x34_59_74),
        /** Grey: news that is neither good nor bad, such as "an update is available". */
        NEUTRAL(0x99_AA_B5),
        GOOD(0x2E_9E_5B),
        BAD(0xC0_39_2B);

        private final int rgb;

        Accent(final int rgb) {
            this.rgb = rgb;
        }
    }

    private final EmbedBuilder embed = new EmbedBuilder();
    private int used;
    private int fields;

    private Card(final String title, final Accent accent) {
        final String shown = cut(Objects.requireNonNull(title, "title"), TITLE);
        embed.setTitle(shown).setColor(accent.rgb);
        used = shown.length();
    }

    public static Card of(final String title, final Accent accent) {
        return new Card(title, accent);
    }

    /** One short line under the title, for the rare value that belongs to no heading. */
    public Card lead(final String text) {
        final String shown = cut(text, Math.min(DESCRIPTION, left()));
        embed.setDescription(shown);
        used += shown.length();
        return this;
    }

    /** A value that sits beside its neighbours: side by side on a wide screen, stacked on a phone. */
    public Card field(final String name, final String value) {
        return add(name, value, true);
    }

    /** A value that takes the full width. */
    public Card wide(final String name, final String value) {
        return add(name, value, false);
    }

    /**
     * One line per item under one heading, full width, continued into further fields when it
     * outgrows one - and summarised by {@code more} once the embed itself is full.
     *
     * @param more what to say about the lines that did not fit, given how many there were
     */
    public Card block(final String name, final List<String> lines, final IntFunction<String> more) {
        if (lines.isEmpty()) {
            return this;
        }
        // Enough for the heading of one more field plus a "+N more" line, so the summary always
        // fits where it is needed - measured from the longest the count can make it.
        final int reserve = CONTINUED.length() + more.apply(lines.size()).length() + 1;
        final List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int budget = left() - name.length();
        int shown = 0;
        for (final String raw : lines) {
            final String line = cut(raw, FIELD_VALUE);
            final int cost = (chunk.isEmpty() ? 0 : 1) + line.length();
            final boolean fitsHere = chunk.length() + cost <= FIELD_VALUE;
            final int nextChunkCost = fitsHere ? 0 : CONTINUED.length();
            final boolean fieldsLeft = fitsHere || fields + chunks.size() + 2 <= FIELDS;
            if (!fieldsLeft || cost + nextChunkCost > budget - reserve) {
                break;
            }
            if (!fitsHere) {
                chunks.add(chunk.toString());
                chunk = new StringBuilder();
            }
            if (!chunk.isEmpty()) {
                chunk.append('\n');
            }
            chunk.append(line);
            budget -= cost + nextChunkCost;
            shown++;
        }
        if (shown < lines.size()) {
            final String summary = more.apply(lines.size() - shown);
            if (chunk.length() + 1 + summary.length() <= FIELD_VALUE) {
                chunk.append(chunk.isEmpty() ? "" : "\n").append(summary);
            } else {
                chunks.add(chunk.toString());
                chunk = new StringBuilder(summary);
            }
        }
        chunks.add(chunk.toString());
        for (int i = 0; i < chunks.size(); i++) {
            add(i == 0 ? name : CONTINUED, chunks.get(i), false);
        }
        return this;
    }

    public Card footer(final String text) {
        final String shown = cut(text, Math.min(2048, left()));
        embed.setFooter(shown);
        used += shown.length();
        return this;
    }

    public Card timestamp(final TemporalAccessor when) {
        embed.setTimestamp(when);
        return this;
    }

    /** An image, usually {@code attachment://<name>} uploaded with the same message. */
    public Card image(final String url) {
        embed.setImage(url);
        return this;
    }

    public MessageEmbed build() {
        return embed.build();
    }

    // ---------------------------------------------------------------- formatting

    /** A transition: the old value plain, the new one bold, an arrow between. */
    public static String arrow(final String from, final String to) {
        return escape(from) + " → " + bold(to);
    }

    public static String bold(final String text) {
        return "**" + escape(text) + "**";
    }

    public static String italic(final String text) {
        return "*" + escape(text) + "*";
    }

    /**
     * Makes text from outside - a player name, an artefact, a failure message - read as itself,
     * so an underscore in a name does not turn the rest of the line italic.
     */
    public static String escape(final String text) {
        return text.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }

    /** {@code 14 s}, {@code 2 min 14 s}, {@code 1 h 3 min}: a duration as a value, not a phrase. */
    public static String duration(final Duration duration) {
        final long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min " + seconds % 60 + " s";
        }
        return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
    }

    // ---------------------------------------------------------------- the arithmetic

    private Card add(final String name, final String value, final boolean inline) {
        final String heading = cut(name, FIELD_NAME);
        if (fields >= FIELDS || heading.length() >= left()) {
            return this;
        }
        final String shown = cut(value, Math.min(FIELD_VALUE, left() - heading.length()));
        embed.addField(heading, shown, inline);
        used += heading.length() + shown.length();
        fields++;
        return this;
    }

    private int left() {
        return TOTAL - used;
    }

    private static String cut(final String text, final int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return limit <= 1 ? "" : text.substring(0, limit - 1) + "…";
    }
}
