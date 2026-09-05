package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.common.command.CommandRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandInbox.AdminCheck#of} on its own, which is the authorisation of the whole transport.
 *
 * <h2>Why it needed its own test</h2>
 * {@code CommandInboxTest} builds its inboxes with {@code request -> admin} - a flag, so that its
 * cases are about what the inbox does with a yes and a no rather than about how the yes is reached.
 * That is the right shape for those tests and it left the shared check itself covered by nothing.
 *
 * <p>The check is not incidental. It is the <em>second</em> of the two admin reads a travelling
 * command gets, and the one that matters: the first happens where the command was typed, and the
 * flag can be revoked while the row waits. A hole here is a revoked admin's command running on the
 * far side, minutes later, on a server they can no longer reach any other way.</p>
 *
 * <h2>The hole it is written against</h2>
 * All three inboxes began with {@code discordId().map(admins::contains).orElse(true)}, reading an
 * absent Discord id as "the console". V11 only requires an id for {@code source='DISCORD'}, and
 * {@code limbo} writes {@code GAME} rows with no id at all, because a waiting room holds no account
 * links. Every one of those ran unauthorised.
 */
class AdminCheckTest {

    private static final String ADMIN_DISCORD = "111111111111111111";
    private static final String OTHER_DISCORD = "222222222222222222";
    private static final UUID ADMIN_MC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_MC = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final CommandInbox.AdminCheck check = CommandInbox.AdminCheck.of(
            () -> Set.of(ADMIN_DISCORD), () -> Set.of(ADMIN_MC));

    private static CommandRequest request(final String source, final String discordId,
                                          final UUID minecraftId) {
        return new CommandRequest(1L, "smp reload", "", source, "someone",
                Optional.ofNullable(discordId), Optional.ofNullable(minecraftId),
                "en", Instant.now().plusSeconds(30));
    }

    @Test
    @DisplayName("the console is the operator, and is identified by being the console")
    void theConsole() {
        // By source and not by "has no identity", which is the whole correction: the schema pins a
        // CONSOLE row to carrying neither id, so this is the one honest way to recognise it.
        assertTrue(check.isAdmin(request("CONSOLE", null, null)));
    }

    @Test
    @DisplayName("a Discord id decides when there is one")
    void byDiscordId() {
        assertTrue(check.isAdmin(request("DISCORD", ADMIN_DISCORD, null)));
        assertFalse(check.isAdmin(request("DISCORD", OTHER_DISCORD, null)));
    }

    @Test
    @DisplayName("a game row with no Discord id falls back to the Minecraft account")
    void byMinecraftAccount() {
        // limbo's rows. It holds no account links, so this is the only identity they carry.
        assertTrue(check.isAdmin(request("GAME", null, ADMIN_MC)));
        assertFalse(check.isAdmin(request("GAME", null, OTHER_MC)));
    }

    @Test
    @DisplayName("a row with neither identity is refused, and used to be admitted")
    void neither() {
        // The original orElse(true). A GAME row is allowed to have no Discord id, so this shape is
        // reachable rather than theoretical.
        assertFalse(check.isAdmin(request("GAME", null, null)));
    }

    @Test
    @DisplayName("the Discord id wins when both are present, and is not softened by the other")
    void discordTakesPrecedence() {
        // An admin's Minecraft account must not rescue a Discord id that is no longer an admin:
        // discord_user.admin is the flag, and the account link is only a way of reaching it.
        assertFalse(check.isAdmin(request("GAME", OTHER_DISCORD, ADMIN_MC)));
        assertTrue(check.isAdmin(request("GAME", ADMIN_DISCORD, OTHER_MC)));
    }

    @Test
    @DisplayName("the sets are read per call, so a revocation lands on the next claimed row")
    void nothingIsCached() {
        final java.util.concurrent.atomic.AtomicReference<Set<String>> held =
                new java.util.concurrent.atomic.AtomicReference<>(Set.of(ADMIN_DISCORD));
        final CommandInbox.AdminCheck live =
                CommandInbox.AdminCheck.of(held::get, java.util.Set::of);

        assertTrue(live.isAdmin(request("DISCORD", ADMIN_DISCORD, null)));
        held.set(Set.of());
        assertFalse(live.isAdmin(request("DISCORD", ADMIN_DISCORD, null)),
                "the admin set is captured once, so a revocation would not reach a waiting row");
    }
}
