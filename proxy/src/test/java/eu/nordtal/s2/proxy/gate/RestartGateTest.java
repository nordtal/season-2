package eu.nordtal.s2.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.access.AccessState;
import eu.nordtal.s2.database.access.MemberState;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.proxy.config.GateSpec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens to an arrival while this proxy is being moved.
 *
 * Arrivals are parked on the standby; a player with a seat is coming back from the swap and is let in.
 */
class RestartGateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestartGateTest.class);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final Messages messages = Messages.load("messages/proxy", Locale.ENGLISH, Locale.GERMAN);
    private final GateMessages gateMessages = new GateMessages(messages, defaults());
    private final FallbackCache locales = new FallbackCache(Duration.ofMinutes(15), Clock.systemUTC());

    @Test
    void bothLanguagesAreThere() {
        assertTrue(messages.hasTranslation(Locale.ENGLISH, "gate.restarting"));
        assertTrue(
                messages.hasTranslation(Locale.GERMAN, "gate.restarting"),
                "every other gate screen has a German half and this one is no different");
    }

    @Test
    void theWindowParksRatherThanRefuses() {
        // The proxy is only unreachable for its own restart; before and after, it parks players on the standby.
        assertEquals(RestartGate.Handling.PARK, RestartGate.decide(true, false));
    }

    @Test
    void aSeatIsNeverTouched() {
        // A held seat is somebody coming back from the swap; refusing them would loop.
        assertEquals(RestartGate.Handling.LET_IN, RestartGate.decide(true, true));
    }

    @Test
    void theDoorIsOpenWhenNothingIsMovingThisProxy() {
        assertEquals(RestartGate.Handling.LET_IN, RestartGate.decide(false, false));
        assertEquals(RestartGate.Handling.LET_IN, RestartGate.decide(false, true));
    }

    @Test
    void anUnknownArrivalStillGetsAScreen() {
        final RestartGate gate =
                new RestartGate(LOGGER, () -> true, uuid -> false, player -> false, gateMessages, locales);

        assertEquals(
                drawn(messages.get(Locale.ENGLISH, "gate.restarting")),
                flatten(gate.refuse(UUID.randomUUID(), "a-stranger")));
    }

    @Test
    void theLanguageComesOutOfMemory() {
        // This proxy stops in a moment, so a round trip for a language could outlive it.
        locales.remember(PLAYER, german());
        final RestartGate gate =
                new RestartGate(LOGGER, () -> true, uuid -> false, player -> false, gateMessages, locales);

        final String rendered = flatten(gate.refuse(PLAYER, "hmtill"));
        assertEquals(drawn(messages.get(Locale.GERMAN, "gate.restarting")), rendered);
        assertNotEquals(drawn(messages.get(Locale.ENGLISH, "gate.restarting")), rendered);
    }

    @Test
    void refusalsAreCounted() {
        final RestartGate gate =
                new RestartGate(LOGGER, () -> true, uuid -> false, player -> false, gateMessages, locales);
        assertEquals(0, gate.refusedCount(), "a proxy that has not been moved has refused nobody");

        for (int attempt = 0; attempt < 7; attempt++) {
            gate.refuse(UUID.randomUUID(), "player-" + attempt);
        }

        assertEquals(
                7,
                gate.refusedCount(),
                "the count is how a run's report can say the transfer did not work for somebody");
        assertEquals(0, gate.parkedCount(), "nobody was parked in this test, only turned away");
    }

    @Test
    void itPromisesNoTime() {
        // The worker restarts it after a stop that has not happened yet, so any time would be a guess.
        for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
            final String raw = messages.get(locale, "gate.restarting");
            assertTrue(raw.matches("(?s).*\\S.*"), raw);
            assertTrue(
                    !raw.matches("(?s).*\\b\\d+\\s*(seconds?|minutes?|Sekunden?|Minuten?)\\b.*"),
                    "the screen promises a duration it cannot keep: " + raw);
        }
    }

    // helpers

    private static AccessState german() {
        return new AccessState(
                PLAYER,
                "1",
                MemberState.MEMBER,
                true,
                Instant.now().plus(Duration.ofDays(1)),
                false,
                false,
                false,
                Locale.GERMAN,
                SeasonPhase.SMP,
                null);
    }

    /** A {@link GateSpec} that answers every method with its declared default. */
    private static GateSpec defaults() {
        return (GateSpec) Proxy.newProxyInstance(
                GateSpec.class.getClassLoader(), new Class<?>[] {GateSpec.class}, new NoConfiguration());
    }

    private static final class NoConfiguration implements InvocationHandler {
        @Override
        public Object invoke(final Object self, final Method method, final Object[] arguments) {
            if (method.isDefault()) {
                try {
                    return InvocationHandler.invokeDefault(self, method, arguments);
                } catch (final Throwable failure) {
                    throw new IllegalStateException(method.getName(), failure);
                }
            }
            final Class<?> returns = method.getReturnType();
            if (returns == String.class) {
                return "";
            }
            if (returns == boolean.class) {
                return false;
            }
            if (returns == int.class) {
                return 1;
            }
            if (returns == long.class) {
                return 1L;
            }
            return null;
        }
    }

    /** A bundle value with its MiniMessage tags removed, as a player reads it. */
    private static String drawn(final String raw) {
        return raw.replaceAll("</?[a-zA-Z_#][a-zA-Z0-9_:.#'\\-]*>", "");
    }

    /** The component's text and every child's, concatenated. */
    private static String flatten(final Component component) {
        final StringBuilder text = new StringBuilder();
        if (component instanceof TextComponent textComponent) {
            text.append(textComponent.content());
        }
        for (final Component child : component.children()) {
            text.append(flatten(child));
        }
        return text.toString();
    }
}
