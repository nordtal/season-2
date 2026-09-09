package eu.nordtal.s2.limbo;

import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the waiting room can actually say every one of the things it can be told to say, in both
 * languages.
 *
 * <p>This is the only test this module can have, and it is worth having. Everything else here is
 * Bukkit - a world, a title, a potion effect, a plugin message - and none of it can be exercised
 * without a running server; what <em>can</em> go wrong without a server is a
 * {@link WaitReason} added on one side of the repository and not translated on the other, which
 * would show a player the literal string {@code limbo.wait.backend.title} on an otherwise black
 * screen. Messages degrades to the key rather than throwing, precisely so that this failure is
 * survivable at runtime - and that is exactly why it needs to fail here instead.
 */
class WaitingTextTest {

    private final Messages messages = Messages.load(WaitingTextTest.class.getClassLoader(),
            "messages/limbo", Locale.ENGLISH, Locale.GERMAN);

    @Test
    void bothBundlesAreLoaded() {
        assertTrue(messages.languages().contains("en"));
        assertTrue(messages.languages().contains("de"),
                "German is not a fallback language, it is one of the two the season ships");
    }

    @Test
    void everyWaitReasonHasATitleAndASubtitleInEveryLanguage() {
        for (final WaitReason reason : WaitReason.values()) {
            for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                assertTrue(messages.hasTranslation(locale, reason.titleKey()),
                        reason + " has no title in " + locale);
                assertTrue(messages.hasTranslation(locale, reason.subtitleKey()),
                        reason + " has no subtitle in " + locale);
            }
        }
    }

    @Test
    void noWaitingTextFallsBackToTheKeyItself() {
        // The runtime symptom of a missing key: the key on screen. Asserted separately from
        // hasTranslation so that a bundle whose file exists but whose key is misspelled is caught.
        for (final WaitReason reason : WaitReason.values()) {
            for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                assertNotEquals(reason.titleKey(), messages.get(locale, reason.titleKey()));
                assertNotEquals(reason.subtitleKey(), messages.get(locale, reason.subtitleKey()));
            }
        }
    }

    @Test
    void everyTitleIsShortEnoughToBeDrawnAtFullSize() {
        // A title is drawn large and centred and is scaled down until it fits, which on a black
        // screen with nothing else on it reads as broken rather than as small. Forty characters is
        // a working limit rather than a protocol one - it is roughly what fits at full size on a
        // narrow window - and it exists so that a translation cannot quietly grow past it.
        for (final WaitReason reason : WaitReason.values()) {
            for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                final String raw = messages.get(locale, reason.titleKey());
                final String drawn = drawn(raw);

                assertFalse(drawn.length() > 40,
                        locale + " " + reason + " title is " + drawn.length() + " characters: "
                                + drawn + " (raw: " + raw + ")");
            }
        }
    }

    /**
     * The title with its MiniMessage tags taken off - what the client actually has to fit.
     *
     * <p>The measurement used to be on the raw value, which was the same thing while these four
     * titles carried no markup and stopped being the same thing the moment they were given the
     * palette: {@code <white>Resource-Pack wird geladen</white>} is 41 raw characters and 25 drawn
     * ones, so the check failed on a title that got <em>shorter</em> on screen than the limit it
     * was written for. A test that counts tags is a test that forbids colour, which is not what
     * this one is about.</p>
     *
     * <p>Stripped by regular expression rather than by parsing: this module compiles against no
     * Adventure at all, and the four values here are ours - a tag in one is a tag somebody typed on
     * purpose, not arbitrary text that has to be tokenised safely.</p>
     */
    private static String drawn(final String raw) {
        return raw.replaceAll("</?[a-zA-Z_#][a-zA-Z0-9_:.#'\\-]*>", "");
    }
}
