package eu.nordtal.s2.commands.info;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which key each of the two printing commands asks for.
 *
 * <h2>Why that is worth a test at all</h2>
 * Because it is the one half of these commands that lives in this module. The <em>text</em> is in
 * the proxy's own bundle - it wants a link and a colour, and the shared bundle carries no markup -
 * so nothing in {@code :commands} can check that it exists. Pinning the key here and pinning its
 * presence in {@code network-control}'s own bundle test is the pair; either one alone leaves a seam
 * that fails as a player being shown the literal string {@code info.rules}.
 */
class InfoCommandsTest {

    /** Records the key rather than printing anything. */
    private static final class FakeInfo implements InfoEffects {

        private final List<String> shown = new ArrayList<>();

        @Override
        public void async(final Runnable work) {
            work.run();
        }

        @Override
        public void warn(final String what, final Throwable failure) {
        }

        @Override
        public void show(final NordtalUser user, final String messageKey) {
            shown.add(messageKey);
        }
    }

    @Test
    @DisplayName("/discord prints the invite key and /rules the rules key")
    void eachAsksForItsOwnKey() {
        final FakeInfo effects = new FakeInfo();
        new ShowDiscord().run(FakeUser.inGame(), Values.none(InfoCommands.DISCORD), effects);
        new ShowRules().run(FakeUser.inGame(), Values.none(InfoCommands.RULES), effects);

        assertEquals(List.of("info.discord", "info.rules"), effects.shown);
    }

    @Test
    @DisplayName("neither says anything of its own, because both are one line of standing text")
    void neitherComposesASentence() {
        final FakeUser user = FakeUser.inGame();
        new ShowDiscord().run(user, Values.none(InfoCommands.DISCORD), new FakeInfo());
        assertEquals(List.of(), user.keys(),
                "a reply on top of the text would be the command explaining the text it just"
                        + " printed");
    }

    @Test
    @DisplayName("both are player commands on the proxy, and neither takes an argument")
    void theShapeOfThem() {
        for (final Declaration declaration : InfoCommands.declarations()) {
            assertFalse(declaration.adminOnly(), declaration.name() + " is admin-only");
            assertFalse(declaration.irreversible(), declaration.name());
            assertEquals(Target.PROXY, declaration.target(),
                    declaration.name() + " has to work in the waiting room, and the waiting room is"
                            + " where a player who cannot get onto a backend is standing");
            assertEquals(java.util.Set.of(Surface.GAME), declaration.surfaces(), declaration.name());
            assertEquals(List.of(), declaration.arguments(), declaration.name());
        }
        assertEquals(List.of("/discord", "/rules"),
                InfoCommands.declarations().stream().map(Declaration::name).toList());
    }
}
