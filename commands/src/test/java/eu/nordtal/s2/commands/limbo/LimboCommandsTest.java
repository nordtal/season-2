package eu.nordtal.s2.commands.limbo;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        public void warn(final String what, final Throwable failure) {
        }

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
    @DisplayName("it is reachable from Discord, which is the whole reason it was folded")
    void itIsOnEverySurface() {
        assertTrue(LimboCommands.RELOAD.surfaces().containsAll(
                        List.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE)),
                "nobody can type this where it runs - a player on limbo is mid-login and has no"
                        + " chat - so a surface missing here is a command that can only be reached"
                        + " from a shell on the production host");
    }

    @Test
    @DisplayName("it is not confirmed, because re-reading a file undoes nothing")
    void itIsNotGuarded() {
        assertTrue(!LimboCommands.RELOAD.irreversible());
    }
}
