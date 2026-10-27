package eu.nordtal.season.proxy.update;

import static eu.nordtal.season.proxy.ProxyRenderer.RENDERER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Occasion;
import eu.nordtal.season.messages.value.Service;
import java.time.Duration;
import java.util.Locale;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;

/**
 * The restart and return lines as a player reads them, rendered the way the proxy sends them.
 */
class RestartLinesRenderTest {

    private static final Locale[] LOCALES = {Locale.ENGLISH, Locale.GERMAN};

    @Test
    void aCountdownLineNamesTheServiceAndLeavesNoBrace() {
        for (final Locale locale : LOCALES) {
            for (final Occasion occasion : Occasion.values()) {
                assertRendered(locale, RestartWatch.countdown(occasion, "smp", 10), occasion != Occasion.MAINTENANCE);
            }
        }
    }

    @Test
    void aNowLineNamesTheServiceAndLeavesNoBrace() {
        for (final Locale locale : LOCALES) {
            for (final Occasion occasion : Occasion.values()) {
                assertRendered(locale, RestartWatch.now(occasion, "smp"), occasion != Occasion.MAINTENANCE);
            }
        }
    }

    @Test
    void theWholeNetworkIsNamedWhenSeveralServicesStop() {
        final String text = plain(Locale.ENGLISH, RestartWatch.now(Occasion.UPDATE, null));
        assertTrue(text.contains("The whole network"), text);
    }

    @Test
    void theWaitingRoomLineNamesTheServiceAndLeavesNoBrace() {
        for (final Locale locale : LOCALES) {
            assertRendered(locale, Homecoming.WAITING_ROOM.with(new Homecoming.Named(Service.SMP, "smp")), true);
        }
    }

    @Test
    void theCalledOffAndFailedLinesNameTheOccasionAndLeaveNoBrace() {
        for (final Locale locale : LOCALES) {
            final RestartWatch.Occasioned update = new RestartWatch.Occasioned(Occasion.UPDATE);
            assertRendered(locale, RestartWatch.CANCELLED.with(update), false);
            assertRendered(locale, RestartWatch.FAILED.with(update), false);
        }
    }

    @Test
    void aServiceWithNoLineOfItsOwnIsNamedByItsComposeName() {
        for (final Locale locale : LOCALES) {
            final String text = plain(locale, RestartWatch.now(Occasion.RECREATE, "steward"));
            assertTrue(text.contains("steward"), locale + ": " + text);
        }
    }

    @Test
    void theTickIsTheSubtitleUnderAnEmptyTitle() {
        final Title title = RENDERER.title(
                Locale.ENGLISH,
                RestartWatch.TICK.with(new RestartWatch.Tick(7)),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO));
        assertEquals("", PlainTextComponentSerializer.plainText().serialize(title.title()));
        assertEquals("7", PlainTextComponentSerializer.plainText().serialize(title.subtitle()));
    }

    private static void assertRendered(final Locale locale, final Sent<?> sent, final boolean namesTheService) {
        final String name = sent.document().name();
        final String text = plain(locale, sent);
        assertFalse(text.contains("{") || text.contains("}"), name + " (" + locale + ") printed a brace: " + text);
        if (namesTheService) {
            assertTrue(text.contains("SMP"), name + " (" + locale + ") does not name the service: " + text);
        }
    }

    private static String plain(final Locale locale, final Sent<?> sent) {
        return PlainTextComponentSerializer.plainText().serialize(RENDERER.format(locale, sent));
    }
}
