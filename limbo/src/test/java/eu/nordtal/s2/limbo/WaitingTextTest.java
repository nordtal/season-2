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
                final String title = messages.get(locale, reason.titleKey());

                assertFalse(title.length() > 40,
                        locale + " " + reason + " title is " + title.length() + " characters: " + title);
            }
        }
    }
}
