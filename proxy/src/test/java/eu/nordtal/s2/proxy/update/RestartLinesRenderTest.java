package eu.nordtal.s2.proxy.update;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The restart and return lines as a player reads them, rendered the way the proxy sends them.
 *
 * The service arrives as a {@link Component}, which fills a {@code <what>} tag and never a {@code {what}}.
 */
class RestartLinesRenderTest {

    private static final Messages MESSAGES_BUNDLE = Messages.load("messages/proxy", Locale.ENGLISH, Locale.GERMAN);

    private static final Locale[] LOCALES = {Locale.ENGLISH, Locale.GERMAN};

    @Test
    void aCountdownLineNamesTheServiceAndLeavesNoBrace() {
        for (final Locale locale : LOCALES) {
            final Component smp = Homecoming.serviceName(MESSAGES_BUNDLE, locale, "smp");
            for (final RunShape.Occasion occasion : RunShape.Occasion.values()) {
                assertRendered(
                        locale, RestartWatch.countdown(occasion, smp, 10), occasion != RunShape.Occasion.MAINTENANCE);
            }
        }
    }

    @Test
    void aNowLineNamesTheServiceAndLeavesNoBrace() {
        for (final Locale locale : LOCALES) {
            final Component smp = Homecoming.serviceName(MESSAGES_BUNDLE, locale, "smp");
            for (final RunShape.Occasion occasion : RunShape.Occasion.values()) {
                assertRendered(locale, RestartWatch.now(occasion, smp), occasion != RunShape.Occasion.MAINTENANCE);
            }
        }
    }

    @Test
    void theWaitingRoomLineNamesTheServiceAndLeavesNoBrace() {
        for (final Locale locale : LOCALES) {
            final Component smp = Homecoming.serviceName(MESSAGES_BUNDLE, locale, "smp");
            assertRendered(locale, MESSAGES.returnSection().waitingRoom(smp), true);
        }
    }

    @Test
    void theCalledOffAndFailedLinesNameTheOccasionAndLeaveNoBrace() {
        for (final Locale locale : LOCALES) {
            final Component update = MessageRenderer.of(MESSAGES_BUNDLE)
                    .format(locale, MESSAGES.restart().occasion().update());
            assertRendered(locale, MESSAGES.restart().cancelled(update), false);
            assertRendered(locale, MESSAGES.restart().failed(update), false);
        }
    }

    private static void assertRendered(final Locale locale, final MessageRef message, final boolean namesTheService) {
        final String text = PlainTextComponentSerializer.plainText()
                .serialize(MessageRenderer.of(MESSAGES_BUNDLE).format(locale, message));
        assertFalse(
                text.contains("{") || text.contains("}"), message.key() + " (" + locale + ") printed a brace: " + text);
        if (namesTheService) {
            assertTrue(text.contains("SMP"), message.key() + " (" + locale + ") does not name the service: " + text);
        }
    }
}
