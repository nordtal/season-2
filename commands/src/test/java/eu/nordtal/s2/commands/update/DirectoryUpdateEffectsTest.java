package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The row says who asked and from where, and it says it from the user - not from a constant each
 * process picked for itself. The proxy picked {@code CONSOLE} for every player who typed there.
 */
class DirectoryUpdateEffectsTest {

    private final FakeUpdateDirectory directory = new FakeUpdateDirectory();
    private final DirectoryUpdateEffects effects = new DirectoryUpdateEffects(directory,
            Runnable::run, (what, failure) -> { }, (id, user) -> { });

    @Test
    @DisplayName("a player is GAME and recorded by name")
    void aPlayer() {
        effects.submit(UpdateKind.REPORT, FakeUser.inGame());
        final FakeUpdateDirectory.Submitted row = directory.submitted.getFirst();
        assertEquals(UpdateSource.GAME, row.source());
        assertEquals("tester", row.requestedBy());
    }

    @Test
    @DisplayName("a Discord member is DISCORD and recorded by id, which is what discord_user is keyed by")
    void aDiscordMember() {
        effects.submit(UpdateKind.REPORT, FakeUser.inDiscord());
        final FakeUpdateDirectory.Submitted row = directory.submitted.getFirst();
        assertEquals(UpdateSource.DISCORD, row.source());
        assertEquals("100000000000000002", row.requestedBy());
    }

    @Test
    @DisplayName("the console is CONSOLE and nobody - not the word 'console'")
    void theConsole() {
        effects.submit(UpdateKind.RESTART, FakeUser.console());
        final FakeUpdateDirectory.Submitted row = directory.submitted.getFirst();
        assertEquals(UpdateSource.CONSOLE, row.source());
        assertNull(row.requestedBy(), "requested_by means a person; a shell in the container is not one");
    }

    @Test
    @DisplayName("the countdown belongs to the kind: the two that stop servers get it, the report does not")
    void theCountdownIsTheKinds() {
        effects.submit(UpdateKind.REPORT, FakeUser.inGame());
        effects.submit(UpdateKind.UPDATE, FakeUser.inGame());
        effects.submit(UpdateKind.RESTART, FakeUser.inGame());
        assertEquals(Duration.ZERO, directory.submitted.get(0).delay());
        assertEquals(UpdateDirectory.UPDATE_COUNTDOWN, directory.submitted.get(1).delay());
        assertEquals(UpdateDirectory.UPDATE_COUNTDOWN, directory.submitted.get(2).delay());
    }
}
