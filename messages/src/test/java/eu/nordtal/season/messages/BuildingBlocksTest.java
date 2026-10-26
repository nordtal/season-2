package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.text.PlainText;
import eu.nordtal.season.messages.value.Kind;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A text names a building block by its key, and every renderer draws the block where it stands. */
class BuildingBlocksTest {

    private static final String DAYS = "values.duration.days";

    private static Messages load() {
        return Messages.load(BuildingBlocksTest.class.getClassLoader(), "messages/test", Locale.GERMAN);
    }

    private static String drawn(final Messages messages, final Locale language, final String text, final Object days) {
        final Messages.Prepared prepared = messages.prepare(
                Viewer.of(language), text, Map.of("days", days), Map.of("days", Kind.NUMBER), TextFormat.PLAIN, "test");
        return PlainText.of(prepared.pieces(), prepared.language(), prepared.zone(), prepared.words());
    }

    @Test
    void aBlockShowsTheTextValueItIsBoundTo() {
        assertEquals("2 days left", drawn(load(), Locale.ENGLISH, "{@" + DAYS + ", n=days} left", 2));
        assertEquals("1 day left", drawn(load(), Locale.ENGLISH, "{@" + DAYS + ", n=days} left", 1));
    }

    @Test
    void aBlockIsDrawnInTheReadersLanguage() {
        assertEquals("noch 2 Tage", drawn(load(), Locale.GERMAN, "noch {@" + DAYS + ", n=days}", 2));
    }

    @Test
    void anUnboundValueOfTheBlockShowsTheTextValueOfTheSameName() {
        final Messages messages = load();
        final Messages.Prepared prepared = messages.prepare(
                Viewer.of(Locale.ENGLISH), "{@" + DAYS + "}", Map.of("n", 3), Map.of(), TextFormat.PLAIN, "test");

        assertEquals("3 days", PlainText.of(prepared.pieces(), prepared.language(), prepared.zone(), prepared.words()));
    }

    @Test
    void anOverrideOfTheBlockReachesEveryTextThatNamesIt() {
        final Messages messages = load();
        final String packaged = PackagedTexts.hash(messages.packaged(Messages.VALUES, DAYS, Locale.ENGLISH));
        messages.override(List.of(new MessageOverride(
                Messages.VALUES, DAYS, "en", 0, "{n, plural, one {# whole day} other {# whole days}}", packaged)));

        assertEquals("2 whole days left", drawn(messages, Locale.ENGLISH, "{@" + DAYS + ", n=days} left", 2));
    }

    @Test
    void aKeyThatIsNoBlockShowsTheReplacementWordOfAText() {
        assertEquals("something left", drawn(load(), Locale.ENGLISH, "{@greeting} left", 2));
    }
}
