package eu.nordtal.s2.discordbot.discord;

import net.dv8tion.jda.api.entities.MessageEmbed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discord's limits, answered once - and measured here, because JDA refuses what breaks them. */
class CardTest {

    @Test
    @DisplayName("a block that outgrows one field continues into the next and keeps every line")
    void aLongBlockContinues() {
        final List<String> lines = lines(10, 200);
        final MessageEmbed embed = Card.of("Update", Card.Accent.NEUTRAL)
                .block("Services", lines, count -> "+" + count + " more").build();

        assertLegal(embed);
        assertTrue(embed.getFields().size() > 1, "2000 characters do not fit one field");
        assertEquals("Services", embed.getFields().getFirst().getName());
        final String all = String.join("\n", embed.getFields().stream().map(MessageEmbed.Field::getValue).toList());
        for (final String line : lines) {
            assertTrue(all.contains(line), "every line fits the embed, so none may be dropped");
        }
    }

    @Test
    @DisplayName("a block bigger than the whole embed is counted, not cut, and the count is right")
    void anOversizedBlockIsSummarised() {
        final List<String> lines = lines(100, 500);
        final MessageEmbed embed = Card.of("Update", Card.Accent.BAD)
                .field("Run", "update")
                .block("Services", lines, count -> "+" + count + " more").build();

        assertLegal(embed);
        final String all = String.join("\n", embed.getFields().stream().map(MessageEmbed.Field::getValue).toList());
        final Matcher more = Pattern.compile("\\+(\\d+) more").matcher(all);
        assertTrue(more.find(), "what did not fit is counted");
        final long shown = lines.stream().filter(all::contains).count();
        assertEquals(lines.size(), shown + Long.parseLong(more.group(1)),
                "shown plus counted is every line, so nothing vanished without a number");
    }

    @Test
    @DisplayName("no more than 25 fields, however many are asked for")
    void twentyFiveFields() {
        final Card card = Card.of("Many", Card.Accent.NORDTAL);
        for (int i = 0; i < 40; i++) {
            card.field("f" + i, "v");
        }
        assertLegal(card.build());
    }

    @Test
    @DisplayName("a transition is an arrow with the new value bold, and a name cannot open markdown")
    void formatting() {
        assertEquals("0.9.3 → **0.9.4**", Card.arrow("0.9.3", "0.9.4"));
        assertEquals("**some\\_player**", Card.bold("some_player"));
        assertEquals("2 min 14 s", Card.duration(Duration.ofSeconds(134)));
        assertEquals("14 s", Card.duration(Duration.ofSeconds(14)));
    }

    private static void assertLegal(final MessageEmbed embed) {
        assertTrue(embed.getLength() <= Card.TOTAL, "the embed is " + embed.getLength());
        assertTrue(embed.getFields().size() <= Card.FIELDS, embed.getFields().size() + " fields");
        for (final MessageEmbed.Field field : embed.getFields()) {
            assertTrue(field.getValue().length() <= Card.FIELD_VALUE, field.getValue().length() + " in a field");
            assertTrue(!field.getValue().contains("```"), "a code block is for something to copy");
        }
    }

    private static List<String> lines(final int count, final int length) {
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final String head = "line-" + i + " ";
            lines.add(head + "x".repeat(length - head.length()));
        }
        return lines;
    }
}
