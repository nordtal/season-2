package eu.nordtal.s2.limbo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.common.message.Messages;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * That the waiting room can actually say every one of the things it can be told to say, in both languages.
 *
 * This is the only test this module can have, and it is worth having. Everything else here is Bukkit - a world, a
 * title, a potion effect, a plugin message - and none of it can be exercised without a running server; what
 * <em>can</em> go wrong without a server is a {@link WaitReason} added on one side of the repository and not
 * translated on the other, which would show a player the literal string {@code limbo.wait.backend.title} on an
 * otherwise black screen. Messages degrades to the key rather than throwing, precisely so that this failure is
 * survivable at runtime - and that is exactly why it needs to fail here instead.
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

    /**
     * The title with its MiniMessage tags taken off - what the client actually has to fit.
     *
     * The raw value differs once a title carries a tag: {@code <white>Resource-Pack wird geladen</white>} is 41 raw
     * characters and 25 drawn ones. A test that counted tags instead would be a test that forbids colour, which is
     * not what this one is about.
     *
     * Stripped by regular expression rather than by parsing: this module compiles against no Adventure at all, and
     * the four values here are ours - a tag in one is a tag somebody typed on purpose, not arbitrary text that has
     * to be tokenised safely.
     */
    private static String drawn(final String raw) {
        return raw.replaceAll("</?[a-zA-Z_#][a-zA-Z0-9_:.#'\\-]*>", "");
    }
}
