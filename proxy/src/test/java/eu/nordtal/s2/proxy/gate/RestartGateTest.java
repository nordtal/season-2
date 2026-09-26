package eu.nordtal.s2.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.proxy.config.GateSpec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens to an arrival while this proxy is being moved (season-2-ops/151).
 *
 * <p>The rule first, because it was turned over on 2026-09-20: nobody is refused any more. An
 * arrival is parked on the standby exactly like everybody who was already connected, and the screen
 * below is what is left when the transfer itself cannot be sent. A player with a seat is coming
 * back <em>from</em> the swap and is not touched at all - which is run 76, where this proxy read a
 * seat for hmtill at 18:46:09 and refused him at 18:46:21.</p>
 *
 * <p>As with {@link MisconfiguredGateTest}, the {@code PostLoginEvent} half is not exercised:
 * constructing one needs a Velocity {@code Player}, which only exists on a running proxy. What is
 * worth asserting is the decision, what the player reads on the failure path, and that the language
 * comes out of memory - because that path runs on a process that is seconds from stopping.</p>
 */
class RestartGateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestartGateTest.class);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final Messages messages = Messages.load("messages/proxy", Locale.ENGLISH, Locale.GERMAN);
    private final GateMessages gateMessages = new GateMessages(messages, defaults());
    private final FallbackCache locales = new FallbackCache(Duration.ofMinutes(15));

    @Test
    @DisplayName("the screen exists in both languages, because both are offered everywhere else")
    void bothLanguagesAreThere() {
        assertTrue(messages.hasTranslation(Locale.ENGLISH, "gate.restarting"));
        assertTrue(
                messages.hasTranslation(Locale.GERMAN, "gate.restarting"),
                "every other gate screen has a German half and this one is no different");
    }

    // ---------------------------------------------------------------- the rule (season-2-ops/151)

    @Test
    @DisplayName("an arrival while the proxy is being moved goes to the standby, not away")
    void theWindowParksRatherThanRefuses() {
        // Till, 2026-09-20: "the proxy should really only be unreachable for its own restart -
        // before and after it must move players onto the standbys properly and may refuse nobody."
        assertEquals(RestartGate.Handling.PARK, RestartGate.decide(true, false));
    }

    @Test
    @DisplayName("a player with a seat is never sent back, because they are coming home")
    void aSeatIsNeverTouched() {
        // RUN 76, EXACTLY. The standby handed hmtill back at 18:46:04; this proxy had his seat in
        // memory at 18:46:09 and turned him away at 18:46:21 and again at 18:46:37 - 47 seconds
        // between the transfer home and getting in. Parking him instead would be no better: it is
        // the same loop with a nicer name.
        assertEquals(RestartGate.Handling.LET_IN, RestartGate.decide(true, true));
    }

    @Test
    @DisplayName("on an ordinary day nothing is in the way")
    void theDoorIsOpenWhenNothingIsMovingThisProxy() {
        assertEquals(RestartGate.Handling.LET_IN, RestartGate.decide(false, false));
        assertEquals(RestartGate.Handling.LET_IN, RestartGate.decide(false, true));
    }

    // ---------------------------------------------------------------- the failure path

    @Test
    @DisplayName("somebody the cache has never seen gets the English screen rather than nothing")
    void anUnknownArrivalStillGetsAScreen() {
        final RestartGate gate =
                new RestartGate(LOGGER, () -> true, uuid -> false, player -> false, gateMessages, locales);

        assertEquals(
                drawn(messages.get(Locale.ENGLISH, "gate.restarting")),
                flatten(gate.refuse(UUID.randomUUID(), "a-stranger")));
    }

    @Test
    @DisplayName("a player the cache knows gets their own language, and no database is asked")
    void theLanguageComesOutOfMemory() {
        // THE WHOLE REASON THE CACHE IS THE SOURCE: this runs on a proxy that stops in a moment.
        // A round trip for a language is a round trip that can outlive the process asking for it.
        locales.remember(PLAYER, german());
        final RestartGate gate =
                new RestartGate(LOGGER, () -> true, uuid -> false, player -> false, gateMessages, locales);

        final String rendered = flatten(gate.refuse(PLAYER, "hmtill"));
        assertEquals(drawn(messages.get(Locale.GERMAN, "gate.restarting")), rendered);
        assertNotEquals(drawn(messages.get(Locale.ENGLISH, "gate.restarting")), rendered);
    }

    @Test
    @DisplayName("every arrival the transfer could not reach is counted, and should be none")
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
    @DisplayName("the screen names no duration, because this proxy cannot know one")
    void itPromisesNoTime() {
        // It is stopping. What starts it again is the worker, on the other side of a stop that has
        // not happened yet - so a number here would be a guess printed as a fact.
        for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
            final String raw = messages.get(locale, "gate.restarting");
            assertTrue(raw.matches("(?s).*\\S.*"), raw);
            assertTrue(
                    !raw.matches("(?s).*\\b\\d+\\s*(seconds?|minutes?|Sekunden?|Minuten?)\\b.*"),
                    "the screen promises a duration it cannot keep: " + raw);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AccessState german() {
        return new AccessState(
                PLAYER,
                "1",
                MemberState.MEMBER,
                true,
                Instant.now().plus(Duration.ofDays(1)),
                false,
                false,
                Locale.GERMAN,
                SeasonPhase.SMP,
                null);
    }

    /**
     * A {@link GateSpec} that answers every method with its declared default, which is all
     * {@link GateMessages} needs here: the restart screen takes no placeholder and no invite.
     */
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

    /** A bundle value with its MiniMessage tags taken off - what a player reads off this screen. */
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
