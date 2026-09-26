package eu.nordtal.s2.commands.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The waiting room's one command.
 *
 * <p>Small, and worth having for one reason: nobody can type it where it runs. A player on limbo is
 * mid-login and has no chat, so before this command could travel, the only way to reload the wording
 * of the eight titles that <em>are</em> this server's whole user interface was a shell on the
 * production host.</p>
 */
class LimboCommandsTest {

    private static final class FakeLimbo implements LimboEffects {

        private boolean succeeds = true;
        private int reloads;

        @Override
        public void async(final Runnable work) {
            work.run();
        }

        @Override
        public void warn(final String what, final Throwable failure) {}

        @Override
        public boolean reloadMessages() {
            reloads++;
            return succeeds;
        }
    }

    private final FakeLimbo limbo = new FakeLimbo();

    private FakeUser run() {
        final FakeUser user = FakeUser.inDiscord();
        final ReloadLimbo command = new ReloadLimbo();
        command.run(user, Values.none(command.declaration()), limbo);
        return user;
    }

    @Test
    @DisplayName("a reload that worked and one that did not are different sentences")
    void bothOutcomes() {
        assertEquals(List.of("limbo.admin.reloaded"), run().keys());

        limbo.succeeds = false;
        assertEquals(List.of("limbo.admin.reload-failed"), run().keys());
        assertEquals(2, limbo.reloads);
    }

    @Test
    @DisplayName("it is console only, since ops/18 took every admin command off game and Discord")
    void itIsConsoleOnly() {
        // Until 2026-09-15 this asserted GAME, DISCORD and CONSOLE, for the reason the class
        // javadoc still gives: nobody can type this where it runs. ops/18 ("alles Admin nur noch
        // Konsole und Web", owner) decided that reasoning no longer wins for an admin command - the
        // wording still needs to be reloadable, and console still reaches every backend, which is
        // exactly what is left once GAME and DISCORD are gone.
        assertEquals(java.util.Set.of(Surface.CONSOLE), LimboCommands.RELOAD.surfaces());
    }

    @Test
    @DisplayName("it is not confirmed, because re-reading a file undoes nothing")
    void itIsNotGuarded() {
        assertTrue(!LimboCommands.RELOAD.irreversible());
    }
}
