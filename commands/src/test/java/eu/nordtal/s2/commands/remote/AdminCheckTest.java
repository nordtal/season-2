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
 * {@code CommandInboxTest} builds its inboxes with {@code request -> admin} - a flag, so that its
 * cases are about what the inbox does with a yes and a no rather than about how the yes is reached.
 * That is the right shape for those tests and it left the shared check itself covered by nothing.
 *
 * The check is not incidental. It is the second of the two admin reads a travelling command gets,
 * and the one that matters: the first happens where the command was typed, and the flag can be
 * revoked while the row waits. A hole here is a revoked admin's command running on the far side,
 * minutes later, on a server they can no longer reach any other way.
 *
 * The hole it is written against: reading an absent Discord id as {@code CONSOLE} would authorise
 * it unconditionally, but an id is only required for {@code source='DISCORD'} - {@code limbo}
 * writes {@code GAME} rows with no id at all, because a waiting room holds no account links.
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
        // By source and not by "has no identity": the schema pins a CONSOLE row to having no identity at all.
        assertTrue(check.isAdmin(request("CONSOLE", null, null)));
    }

    @Test
    void aDiscordIdDecidesWhenThereIsOne() {
        assertTrue(check.isAdmin(request("DISCORD", ADMIN_DISCORD, null)));
        assertFalse(check.isAdmin(request("DISCORD", OTHER_DISCORD, null)));
    }

    @Test
    void aGameRowWithNoDiscordIdFallsBackToTheMinecraftAccount() {
        // limbo's rows. It holds no account links, so this is the only identity they carry.
        assertTrue(check.isAdmin(request("GAME", null, ADMIN_MC)));
        assertFalse(check.isAdmin(request("GAME", null, OTHER_MC)));
    }

    @Test
    void aRowWithNeitherIdentityIsRefusedAndUsedToBeAdmitted() {
        // The original orElse(true). A GAME row is allowed to have no Discord id.
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
