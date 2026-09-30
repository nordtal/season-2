package eu.nordtal.s2.proxy.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.network.NetworkSnapshot;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The MOTD placeholder substitution, the only logic on the ping path.
 *
 * {@link ProxyServer} is a {@link Proxy} since this needs two of its methods.
 */
class PlaceholdersTest {

    private static final NetworkSnapshot SNAPSHOT =
            new NetworkSnapshot("RUNNING", 12, 5, 24, 9, 15, "the-nether", 42, 3, 8, 1234L, 57);

    @Test
    void theProxysOwnNumbersComeFromTheProxy() {
        assertEquals("7 of 500 online, phase SMP", apply("{online} of {max} online, phase {phase}"));
    }

    @Test
    void aPerServerCountNamesTheServerAsVelocitySpellsIt() {
        assertEquals("3 on the smp, 0 in limbo", apply("{players:smp} on the smp, {players:limbo} in limbo"));
    }

    @Test
    void aServerThisProxyDoesNotHaveIsZeroAndNotAnError() {
        // A ping that throws would make the network look unreachable.
        assertEquals("0", apply("{players:does-not-exist}"));
    }

    @Test
    void theHungerGamesAndSmpNumbersComeFromTheSnapshot() {
        assertEquals(
                "RUNNING 12 5 24 9 15",
                apply("{hg-state} {hg-teams} {hg-teams-alive} {hg-participants} {hg-alive} {hg-eliminated}"));
        assertEquals(
                "the-nether 42 3 8 1234 57",
                apply("{smp-milestone} {smp-milestone-progress} {smp-milestones-done} "
                        + "{smp-milestones-total} {smp-aura-total} {smp-players}"));
    }

    @Test
    void anEmptySnapshotRendersZeroesRatherThanNothing() {
        // What a proxy shows before its first successful refresh.
        assertEquals(
                "0 teams,  running",
                Placeholders.apply(
                        "{hg-teams} teams, {hg-state} running",
                        proxy(),
                        SeasonPhase.PRE_EVENT,
                        500,
                        NetworkSnapshot.EMPTY,
                        "any moment now"));
    }

    @Test
    void anUnknownPlaceholderIsLeftStandingSoTheTypoIsVisible() {
        // A typo that vanishes is never found; Messages treats an unknown parameter the same way.
        assertEquals("{hg-alve} and {nonsense}", apply("{hg-alve} and {nonsense}"));
    }

    @Test
    void aValueContainingATagCannotInjectMiniMessage() {
        // Substituting before parsing lets a MOTD colour a number, at the cost of an injectable bracket.
        final NetworkSnapshot hostile =
                new NetworkSnapshot("", 0, 0, 0, 0, 0, "<red>everything after this", 0, 0, 0, 0L, 0);

        assertEquals(
                "\\<red>everything after this",
                Placeholders.apply("{smp-milestone}", proxy(), SeasonPhase.SMP, 500, hostile, ""));
    }

    @Test
    void miniMessageInTheTemplateItselfIsUntouched() {
        assertEquals(
                "<gradient:#5ec2ff:#a8e6ff>nordtal</gradient><newline>7 online",
                apply("<gradient:#5ec2ff:#a8e6ff>nordtal</gradient><newline>{online} online"));
    }

    @Test
    void anUnclosedBraceIsKeptRatherThanSwallowingTheRestOfTheLine() {
        assertEquals("nordtal {online", apply("nordtal {online"));
    }

    @Test
    void aTemplateWithNoPlaceholdersIsReturnedUnchanged() {
        assertEquals("nordtal.eu", apply("nordtal.eu"));
    }

    // helpers

    private static String apply(final String template) {
        return Placeholders.apply(template, proxy(), SeasonPhase.SMP, 500, SNAPSHOT, "3 days 4 hours");
    }

    /** A proxy with seven players online, three on {@code smp} and none in {@code limbo}. */
    private static ProxyServer proxy() {
        final Map<String, Integer> perServer = Map.of("smp", 3, "limbo", 0);
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class},
                (instance, method, arguments) -> switch (method.getName()) {
                    case "getPlayerCount" -> 7;
                    case "getServer" ->
                        Optional.ofNullable(perServer.get((String) arguments[0]))
                                .map(PlaceholdersTest::server);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static RegisteredServer server(final int players) {
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[] {RegisteredServer.class},
                (instance, method, arguments) -> switch (method.getName()) {
                    // nCopies, since List.of and copyOf reject nulls and only the size matters.
                    case "getPlayersConnected" -> Collections.nCopies(players, null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
