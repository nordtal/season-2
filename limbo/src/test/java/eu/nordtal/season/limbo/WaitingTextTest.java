package eu.nordtal.season.limbo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.messages.Messages;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The waiting room can say every one of the things it can be told to say, in both languages.
 *
 * A {@link WaitReason} missing a translation shows the bare key at runtime, so it has to fail here.
 */
class WaitingTextTest {

    private final Messages messages =
            Messages.load(WaitingTextTest.class.getClassLoader(), "messages/limbo", Locale.ENGLISH, Locale.GERMAN);
    private final LimboMessages.Limbo.Wait screens =
            LimboMessages.MESSAGES.limbo().waiting();

    @Test
    void bothBundlesAreLoaded() {
        assertTrue(messages.languages().contains("en"));
        assertTrue(
                messages.languages().contains("de"),
                "German is not a fallback language, it is one of the two the season ships");
    }

    @Test
    void everyWaitReasonHasATitleAndASubtitleInEveryLanguage() {
        for (final WaitReason reason : WaitReason.values()) {
            for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                assertTrue(
                        messages.hasTranslation(
                                locale, screens.of(reason).title().key()),
                        reason + " has no title in " + locale);
                assertTrue(
                        messages.hasTranslation(
                                locale, screens.of(reason).subtitle().key()),
                        reason + " has no subtitle in " + locale);
            }
        }
    }

    @Test
    void noWaitingTextFallsBackToTheKeyItself() {
        // Asserted separately from hasTranslation, so a bundle whose key is misspelled is caught too.
        for (final WaitReason reason : WaitReason.values()) {
            for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                assertNotEquals(
                        screens.of(reason).title().key(),
                        messages.get(locale, screens.of(reason).title().key()));
                assertNotEquals(
                        screens.of(reason).subtitle().key(),
                        messages.get(locale, screens.of(reason).subtitle().key()));
            }
        }
    }

    @Test
    void everyTitleIsShortEnoughToBeDrawnAtFullSize() {
        // A title scaled down to fit reads as broken on a black screen; forty is roughly the full-size limit.
        for (final WaitReason reason : WaitReason.values()) {
            for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                final String raw =
                        messages.get(locale, screens.of(reason).title().key());
                final String drawn = drawn(raw);

                assertFalse(
                        drawn.length() > 40,
                        locale + " " + reason + " title is " + drawn.length() + " characters: " + drawn + " (raw: "
                                + raw + ")");
            }
        }
    }

    /** The title without its MiniMessage tags, which is what the client has to fit. */
    private static String drawn(final String raw) {
        return raw.replaceAll("</?[a-zA-Z_#][a-zA-Z0-9_:.#'\\-]*>", "");
    }
}
