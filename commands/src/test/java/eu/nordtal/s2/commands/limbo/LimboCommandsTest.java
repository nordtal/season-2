package eu.nordtal.s2.commands.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The waiting room's one command, which nobody can type where it runs, since a player on limbo has no chat. */
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
    void aReloadThatWorkedAndOneThatDidNotAreDifferentSentences() {
        assertEquals(List.of("limbo.admin.reloaded"), run().keys());

        limbo.succeeds = false;
        assertEquals(List.of("limbo.admin.reload-failed"), run().keys());
        assertEquals(2, limbo.reloads);
    }

    @Test
    void itIsConsoleOnlyAndOffGameAndDiscord() {
        // Admin commands lose GAME and DISCORD; the console remains.
        assertEquals(java.util.Set.of(Surface.CONSOLE), LimboCommands.RELOAD.surfaces());
    }

    @Test
    void itIsNotConfirmedBecauseReReadingAFileUndoesNothing() {
        assertTrue(!LimboCommands.RELOAD.irreversible());
    }
}
