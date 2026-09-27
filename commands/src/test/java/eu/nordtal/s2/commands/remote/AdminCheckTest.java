package eu.nordtal.s2.commands.remote;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.command.CommandRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link CommandInbox.AdminCheck#of} on its own, which is the authorisation of the whole transport.
 *
 * It runs when a row is claimed, so an admin flag revoked while the row waited must refuse it.
 */
class AdminCheckTest {

    private static final String ADMIN_DISCORD = "111111111111111111";
    private static final String OTHER_DISCORD = "222222222222222222";
    private static final UUID ADMIN_MC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_MC = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final CommandInbox.AdminCheck check =
            CommandInbox.AdminCheck.of(() -> Set.of(ADMIN_DISCORD), () -> Set.of(ADMIN_MC));

    private static CommandRequest request(final String source, final String discordId, final UUID minecraftId) {
        return new CommandRequest(
                1L,
                "smp reload",
                "",
                source,
                "someone",
                Optional.ofNullable(discordId),
                Optional.ofNullable(minecraftId),
                "en",
                Instant.now().plusSeconds(30));
    }

    @Test
    void theConsoleIsTheOperatorAndIsIdentifiedByBeingTheConsole() {
        // By source, not by missing identity: the schema pins a CONSOLE row to having none.
        assertTrue(check.isAdmin(request("CONSOLE", null, null)));
    }

    @Test
    void aDiscordIdDecidesWhenThereIsOne() {
        assertTrue(check.isAdmin(request("DISCORD", ADMIN_DISCORD, null)));
        assertFalse(check.isAdmin(request("DISCORD", OTHER_DISCORD, null)));
    }

    @Test
    void aGameRowWithNoDiscordIdFallsBackToTheMinecraftAccount() {
        // limbo's rows carry no account link, so this is their only identity.
        assertTrue(check.isAdmin(request("GAME", null, ADMIN_MC)));
        assertFalse(check.isAdmin(request("GAME", null, OTHER_MC)));
    }

    @Test
    void aRowWithNeitherIdentityIsRefusedAndUsedToBeAdmitted() {
        // A GAME row may have no Discord id, and with neither identity it is refused.
        assertFalse(check.isAdmin(request("GAME", null, null)));
    }

    @Test
    void theDiscordIdWinsWhenBothArePresentAndIsNotSoftenedByTheOther() {
        // An admin's Minecraft account must not rescue a Discord id that is no longer an admin's.
        assertFalse(check.isAdmin(request("GAME", OTHER_DISCORD, ADMIN_MC)));
        assertTrue(check.isAdmin(request("GAME", ADMIN_DISCORD, OTHER_MC)));
    }

    @Test
    void theSetsAreReadPerCallSoARevocationLandsOnTheNextClaimedRow() {
        final java.util.concurrent.atomic.AtomicReference<Set<String>> held =
                new java.util.concurrent.atomic.AtomicReference<>(Set.of(ADMIN_DISCORD));
        final CommandInbox.AdminCheck live = CommandInbox.AdminCheck.of(held::get, java.util.Set::of);

        assertTrue(live.isAdmin(request("DISCORD", ADMIN_DISCORD, null)));
        held.set(Set.of());
        assertFalse(
                live.isAdmin(request("DISCORD", ADMIN_DISCORD, null)),
                "the admin set is captured once, so a revocation would not reach a waiting row");
    }
}
