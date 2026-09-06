package eu.nordtal.s2.smp.announce;

import eu.nordtal.s2.commands.announce.AnnounceCommands;
import eu.nordtal.s2.commands.remote.RequestArguments;
import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.command.NewCommandRequest;
import eu.nordtal.s2.common.message.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One row per language, in the shape the bot's inbox decodes, rendered from the real bundle.
 *
 * <p>What this pins is the seam: the SMP writes {@code announce de <text>} and the bot reads it
 * back through {@code RequestArguments#decode}. A row the decoder refuses is a row that settles as
 * FAILED in a table nobody watches, so the encoding is asserted by decoding it here.</p>
 */
class AnnouncerTest {

    private static final Messages MESSAGES = Messages.load(AnnouncerTest.class.getClassLoader(),
            "messages/smp", Locale.GERMAN, Locale.ENGLISH);

    private static final class Rows implements CommandRequests {
        final List<NewCommandRequest> submitted = new ArrayList<>();
        RuntimeException refuse;

        @Override
        public long submit(final NewCommandRequest request) {
            if (refuse != null) {
                throw refuse;
            }
            submitted.add(request);
            return submitted.size();
        }

        @Override
        public Optional<eu.nordtal.s2.common.command.CommandRequest> claim(final String target) {
            return Optional.empty();
        }

        @Override
        public void finish(final long id, final boolean ok, final String result) {
        }

        @Override
        public boolean expire(final long id) {
            return false;
        }

        @Override
        public Optional<CommandOutcome> outcome(final long id) {
            return Optional.empty();
        }

        @Override
        public int deleteSettledOlderThan(final int days) {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("a milestone is announced once per language, rendered in that language")
    void oneRowPerLanguage() {
        final Rows rows = new Rows();
        final List<String> warnings = new ArrayList<>();
        final Announcer announcer = new Announcer(rows, MESSAGES, Runnable::run,
                (message, failure) -> warnings.add(message));

        announcer.announce("smp.announce.milestone.border",
                locale -> Map.of("milestone", locale.getLanguage().equals("de") ? "Aufbruch" : "Departure"));

        assertEquals(List.of(), warnings);
        assertEquals(Announcer.LANGUAGES.size(), rows.submitted.size());
        for (final NewCommandRequest row : rows.submitted) {
            assertEquals("BOT", row.target());
            assertEquals("announce", row.command());
            assertEquals("CONSOLE", row.source(), "a server has no Discord or Minecraft identity");
            assertEquals(Announcer.SENDER, row.requestedBy());
            assertTrue(row.discordId().isEmpty() && row.minecraftId().isEmpty());
            // The bot decodes what the SMP encoded: same declaration, same codec, no JSON.
            final var values = RequestArguments.decode(AnnounceCommands.ANNOUNCE, row.arguments());
            assertEquals(row.locale(), values.string("language"));
            assertEquals(row.locale().equals("de") ? "Aufbruch ist geschafft - die Grenze wächst."
                    : "Departure is complete - the border grows.", values.string("text"));
        }
    }

    @Test
    @DisplayName("a database that refuses the row is a warning, once per language, and nothing else")
    void aRefusedRowIsAWarning() {
        final Rows rows = new Rows();
        rows.refuse = new IllegalStateException("pool exhausted");
        final List<String> warnings = new ArrayList<>();
        new Announcer(rows, MESSAGES, Runnable::run, (message, failure) -> warnings.add(message))
                .announce("smp.announce.farm-reset", Map.of("minutes", 30));
        assertEquals(Announcer.LANGUAGES.size(), warnings.size());
        assertTrue(warnings.getFirst().contains("smp.announce.farm-reset"));
    }
}
