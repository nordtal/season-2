package eu.nordtal.s2.networkcontrol.ping;

import eu.nordtal.s2.common.network.NetworkSnapshot;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import eu.nordtal.s2.common.SeasonPhase;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The MOTD placeholder substitution, which is the one piece of the ping path with any logic in it.
 * <p>
 * Everything else on that path is a field read by design - a ping is unauthenticated and arrives in
 * bursts - so this is where the behaviour worth pinning lives: what an unknown name does, what a
 * value containing MiniMessage syntax does, and what is shown when the database has never answered.
 * </p>
 * <p>
 * {@link ProxyServer} is served by a {@link Proxy} rather than a hand-written fake. The interface
 * has some twenty methods and this needs two of them; a fake implementing the rest to throw would
 * be longer than the class under test.
 * </p>
 */
class PlaceholdersTest {

    private static final NetworkSnapshot SNAPSHOT = new NetworkSnapshot(
            "RUNNING", 12, 5, 24, 9, 15, "the-nether", 42, 3, 8, 1234L, 57);

    @Test
    void theProxysOwnNumbersComeFromTheProxy() {
        assertEquals("7 of 500 online, phase SMP",
                apply("{online} of {max} online, phase {phase}"));
    }

    @Test
    void aPerServerCountNamesTheServerAsVelocitySpellsIt() {
        assertEquals("3 on the smp, 0 in limbo", apply("{players:smp} on the smp, {players:limbo} in limbo"));
    }

    @Test
    void aServerThisProxyDoesNotHaveIsZeroAndNotAnError() {
        // The MOTD is not the place to discover a routing misconfiguration: gate.yml's names are
        // checked where they are used, and a ping that throws would make the network look
        // unreachable rather than misconfigured.
        assertEquals("0", apply("{players:does-not-exist}"));
    }

    @Test
    void theHungerGamesAndSmpNumbersComeFromTheSnapshot() {
        assertEquals("RUNNING 12 5 24 9 15",
                apply("{hg-state} {hg-teams} {hg-teams-alive} {hg-participants} {hg-alive} {hg-eliminated}"));
        assertEquals("the-nether 42 3 8 1234 57",
                apply("{smp-milestone} {smp-milestone-progress} {smp-milestones-done} "
                        + "{smp-milestones-total} {smp-aura-total} {smp-players}"));
    }

    @Test
    void anEmptySnapshotRendersZeroesRatherThanNothing() {
        // What a proxy shows before its first successful refresh, and if one never succeeds. The
        // MOTD stays a MOTD.
        assertEquals("0 teams,  running", Placeholders.apply("{hg-teams} teams, {hg-state} running",
                proxy(), SeasonPhase.PRE_EVENT, 500, NetworkSnapshot.EMPTY, "any moment now"));
    }

    @Test
    void anUnknownPlaceholderIsLeftStandingSoTheTypoIsVisible() {
        // A typo that vanishes is a typo nobody finds. This matches how Messages treats a parameter
        // it was not given.
        assertEquals("{hg-alve} and {nonsense}", apply("{hg-alve} and {nonsense}"));
    }

    @Test
    void aValueContainingATagCannotInjectMiniMessage() {
        // Substitution happens before parsing, which is what lets a MOTD colour a number. The cost
        // is that a value with an angle bracket in it could otherwise open a tag - and
        // smp-milestone is a key out of a YAML file somebody edits.
        final NetworkSnapshot hostile = new NetworkSnapshot("", 0, 0, 0, 0, 0,
                "<red>everything after this", 0, 0, 0, 0L, 0);

        assertEquals("\\<red>everything after this", Placeholders.apply("{smp-milestone}", proxy(),
                SeasonPhase.SMP, 500, hostile, ""));
    }

    @Test
    void miniMessageInTheTemplateItselfIsUntouched() {
        assertEquals("<gradient:#5ec2ff:#a8e6ff>nordtal</gradient><newline>7 online",
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

    // ---------------------------------------------------------------- helpers

    private static String apply(final String template) {
        return Placeholders.apply(template, proxy(), SeasonPhase.SMP, 500, SNAPSHOT, "3 days 4 hours");
    }

    /** A proxy with seven players online, three of them on {@code smp} and none in {@code limbo}. */
    private static ProxyServer proxy() {
        final Map<String, Integer> perServer = Map.of("smp", 3, "limbo", 0);
        return (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (instance, method, arguments) -> switch (method.getName()) {
                    case "getPlayerCount" -> 7;
                    case "getServer" -> Optional.ofNullable(perServer.get((String) arguments[0]))
                            .map(PlaceholdersTest::server);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static RegisteredServer server(final int players) {
        return (RegisteredServer) Proxy.newProxyInstance(RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (instance, method, arguments) -> switch (method.getName()) {
                    // nCopies, not List.of/copyOf: this only has to have a size, and the two
                    // factory methods reject the nulls that fill it.
                    case "getPlayersConnected" -> Collections.nCopies(players, null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
