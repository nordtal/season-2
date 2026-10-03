package eu.nordtal.s2.discordbot;

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

    /** The one colour of every embed; an outcome is an emoji in the content, never a colour. */
    static final int COLOUR = 0x34_59_74;

    private final EmbedBuilder embed = new EmbedBuilder();
    private int used;
    private int fields;

    private Card(final String title) {
        final String shown = cut(Objects.requireNonNull(title, "title"), TITLE);
        embed.setTitle(shown).setColor(COLOUR);
        used = shown.length();
    }

    public static Card of(final String title) {
        return new Card(title);
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

    /** Escapes Discord markdown in text from outside, such as a player name, as the Discord target does. */
    public static String escape(final String text) {
        return DiscordRenderer.escape(text);
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
