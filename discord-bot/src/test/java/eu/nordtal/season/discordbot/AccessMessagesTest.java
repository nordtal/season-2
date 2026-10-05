package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.spec.MessageSpecCheck;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every key of the bundle has a method with a name, and every text names exactly its arguments. */
class AccessMessagesTest {

    @Test
    void theSpecAndTheBundleAgree() {
        assertEquals(List.of(), MessageSpecCheck.problems(AccessMessages.class));
    }

    /** A counted "in 3 days" goes stale once sent; a Discord timestamp counts down in the client. */
    @Test
    void oneDayIsOneDayInBothLanguages() {
        final eu.nordtal.season.messages.Messages bundle = eu.nordtal.season.messages.Messages.load(
                "messages/access", java.util.Locale.ENGLISH, java.util.Locale.GERMAN);
        final AccessMessages.Dm.Granted granted = AccessMessages.MESSAGES.dm().grantedSection();

        assertTrue(
                bundle.format(java.util.Locale.GERMAN, granted.admin(1, java.time.Instant.EPOCH))
                        .contains("**1 Tag**"),
                bundle.format(java.util.Locale.GERMAN, granted.admin(1, java.time.Instant.EPOCH)));
        assertTrue(bundle.format(java.util.Locale.ENGLISH, granted.admin(1, java.time.Instant.EPOCH))
                .contains("**1 day**"));
        assertTrue(bundle.format(java.util.Locale.GERMAN, granted.admin(30, java.time.Instant.EPOCH))
                .contains("**30 Tage**"));
    }

    @Test
    void noDeadlineIsCountedOutInWords() throws IOException {
        final Pattern counted = Pattern.compile("\\b(days?|hours?|Tagen?|Stunden?)\\b");
        for (final String tag : List.of("en", "de")) {
            final Properties bundle = bundle(tag);
            for (final String key : List.of("dm.expiring", "purchase.link.ttl")) {
                final String text = bundle.getProperty(key);
                assertFalse(counted.matcher(text).find(), tag + " " + key + " counts in words: " + text);
            }
            for (final String key : List.of("dm.expiring", "dm.expired")) {
                assertTrue(bundle.getProperty(key).contains("{channel}"), tag + " " + key + " names no channel");
            }
        }
    }

    private static Properties bundle(final String tag) throws IOException {
        final Properties bundle = new Properties();
        try (Reader in = new InputStreamReader(
                Objects.requireNonNull(
                        AccessMessagesTest.class.getResourceAsStream("/messages/access/" + tag + ".properties")),
                StandardCharsets.UTF_8)) {
            bundle.load(in);
        }
        return bundle;
    }
}
