package eu.nordtal.s2.discordbot;

import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/**
 * Builds every embed the bot sends: a title, one-word headings, and values that are data.
 *
 * Discord's size limits are enforced here; {@link #block} packs lines into fields and counts what does not fit.
 */
public final class Card {

    static final int FIELDS = 25;
    static final int FIELD_VALUE = 1024;
    static final int FIELD_NAME = 256;
    static final int TITLE = 256;
    static final int DESCRIPTION = 4096;
    static final int TOTAL = 6000;

    /** The name of a block's continuation field, since Discord drops a field with an empty name. */
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
     * Adds one line per item under one heading, continued into further fields when it outgrows one.
     *
     * @param more what to say about the lines that did not fit, given how many there were
     */
    public Card block(final String name, final List<String> lines, final IntFunction<String> more) {
        if (lines.isEmpty()) {
            return this;
        }
        // Room for one more heading plus a "+N more" line.
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

    /** Renders a transition: the old value plain, the new one bold, an arrow between. */
    public static String arrow(final String from, final String to) {
        return escape(from) + " → " + bold(to);
    }

    public static String bold(final String text) {
        return "**" + escape(text) + "**";
    }

    public static String italic(final String text) {
        return "*" + escape(text) + "*";
    }

    /** Escapes Discord markdown in text from outside, such as a player name. */
    public static String escape(final String text) {
        return text.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }

    /** Renders a duration as {@code 14 s}, {@code 2 min 14 s} or {@code 1 h 3 min}. */
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
