package eu.nordtal.s2.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.spec.MessageSchema;
import eu.nordtal.s2.messages.spec.MessageSpecCheck;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.value.GameContent;
import eu.nordtal.s2.messages.value.Money;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every value kind as a reader in English and in German reads it in plain text. */
class ValueKindsTest {

    private static final KindsMessages KINDS = MessageSpecs.create(KindsMessages.class);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final PlayerContext ALEX =
            PlayerContext.of(PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a")), "Alex");

    @TempDir
    static Path schemas;

    private static Messages messages;

    /** The schema is the spec's own, as the build writes it, so the kinds below are the declared ones. */
    @BeforeAll
    static void load() throws IOException {
        final Path schema = schemas.resolve("messages/kinds/schema.json");
        Files.createDirectories(schema.getParent());
        Files.writeString(schema, MessageSchema.json(KindsMessages.class), StandardCharsets.UTF_8);
        final ClassLoader loader =
                new URLClassLoader(new URL[] {schemas.toUri().toURL()}, ValueKindsTest.class.getClassLoader());
        messages = Messages.load(loader, "messages/kinds", Locale.GERMAN);
    }

    private static String en(final MessageRef message) {
        return messages.format(Locale.ENGLISH, message);
    }

    private static String de(final MessageRef message) {
        return messages.format(Locale.GERMAN, message);
    }

    @Test
    void theFixtureIsAValidBundle() {
        assertEquals(List.of(), MessageSpecCheck.problems(KindsMessages.class));
    }

    @Test
    void oneDayIsSingularInBothLanguages() {
        assertEquals("1 day", en(KINDS.days(1)));
        assertEquals("1 Tag", de(KINDS.days(1)));
        assertEquals("2 Tage", de(KINDS.days(2)));
        assertEquals("0 Tage", de(KINDS.days(0)));
    }

    @Test
    void aDurationShowsItsTwoLargestUnits() {
        final Duration left = Duration.ofMinutes(125);
        assertEquals("2 hours and 5 minutes left", en(KINDS.left(left)));
        assertEquals("noch 2 Stunden und 5 Minuten", de(KINDS.left(left)));
        assertEquals("noch 1 Tag", de(KINDS.left(Duration.ofDays(1).plusMinutes(3))));
        assertEquals("2h 5m left", en(KINDS.leftShort(left)));
        assertEquals("noch 2 Std. 5 Min.", de(KINDS.leftShort(left)));
        assertEquals("2:05:00", en(KINDS.leftClock(left)));
        assertEquals("0:09", en(KINDS.leftClock(Duration.ofSeconds(9))));
        assertEquals("0 seconds left", en(KINDS.left(Duration.ZERO)));
    }

    @Test
    void moneyIsWrittenAsTheReadersLanguageWritesIt() {
        assertEquals("It costs €3.00.", en(KINDS.price(Money.euroCents(300))));
        assertEquals("Kostet 3,00 €.", de(KINDS.price(Money.euroCents(300))));
    }

    @Test
    void anInstantIsShownInTheReadersZone() {
        final Instant at = Instant.parse("2026-10-03T18:40:00Z");
        assertEquals(
                "Beginnt am 03.10.2026 um 20:40.",
                messages.format(new Viewer(Locale.GERMAN, BERLIN, null), KINDS.starts(at)));
        assertEquals("Beginnt am 03.10.2026 um 18:40.", de(KINDS.starts(at)), "without a zone, the network's");
    }

    @Test
    void aRelativeInstantIsTheMomentItselfOutsideDiscord() {
        final Instant at = Instant.parse("2026-10-03T18:40:00Z");
        assertEquals(
                "Läuft 03.10.2026, 20:40 ab.",
                messages.format(new Viewer(Locale.GERMAN, BERLIN, null), KINDS.deadline(at)));
    }

    @Test
    void aListEndsWithTheReadersConjunction() {
        assertEquals("Alex, Sam and Kim joined.", en(KINDS.players(List.of("Alex", "Sam", "Kim"))));
        assertEquals("Alex und Sam sind da.", de(KINDS.players(List.of("Alex", "Sam"))));
    }

    @Test
    void aChoiceSelectsItsCase() {
        assertEquals("offen", de(KINDS.open(true)));
        assertEquals("zu", de(KINDS.open(false)));
    }

    @Test
    void aPlayerRoleKnowsWhetherItIsTheReader() {
        assertEquals("Alex won.", en(KINDS.won(ALEX)));
        assertEquals("Du hast gewonnen.", messages.format(new Viewer(Locale.GERMAN, null, ALEX), KINDS.won(ALEX)));
    }

    @Test
    void gameContentReadsInEnglishOutsideTheGame() {
        assertEquals("Diamond Sword gefunden.", de(KINDS.found(GameContent.of("item.minecraft.diamond_sword"))));
    }

    @Test
    void aMissingValueReadsAsItsKindsReplacementWord() {
        assertEquals("a while left", en(new MessageRef("left", Map.of())));
        assertEquals("noch eine Weile", de(new MessageRef("left", Map.of())));
        assertEquals("jemand hat gewonnen.", de(new MessageRef("won", Map.of())));
    }

    @Test
    void aValueOfNoKindReadsAsItsKindsReplacementWordAndNeverAsItsToString() {
        assertEquals("a while left", en(new MessageRef("left", Map.of("left", new Object()))));
        assertEquals("It costs an amount.", en(new MessageRef("price", Map.of("price", Thread.currentThread()))));
    }
}
