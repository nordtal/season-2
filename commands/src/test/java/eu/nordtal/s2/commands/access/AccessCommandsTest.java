package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything {@code /access} decides, without a guild, a bank or a bot.
 *
 * <h2>What was previously unaskable</h2>
 * All of it. These four commands lived as JDA handlers building English strings with a
 * {@code StringBuilder}, so "what does {@code /access settle} say when the reference is already
 * paid?" needed a real guild, a real admin and a real payment. Two of the answers below were also
 * simply absent before: there was no admin check at all, and no sentence for a member who has left
 * the guild.
 */
class AccessCommandsTest {

    private static final String DISCORD = "100000000000000009";
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final class FakeBot implements AccessEffects {

        final List<String> did = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Status status;
        Instant grantedUntil = Instant.parse("2026-12-01T00:00:00Z");
        int revoked = 3;
        boolean unlinked = true;
        Settled settlement = new Settled(Settlement.BOOKED,
                Instant.parse("2026-12-01T00:00:00Z"), 60, "OPEN");
        boolean reloaded = true;
        List<String> unknownKeys = List.of();
        RuntimeException failure;

        @Override
        public void async(final Runnable work) {
            work.run();
        }

        @Override
        public void warn(final String what, final Throwable cause) {
            warnings.add(what);
        }

        @Override
        public Optional<Status> status(final String discordId) {
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(status);
        }

        @Override
        public Instant grant(final String discordId, final int days, final NordtalUser by) {
            if (failure != null) {
                throw failure;
            }
            did.add("grant " + discordId + " " + days + " by " + by.name());
            return grantedUntil;
        }

        @Override
        public int revoke(final String discordId, final NordtalUser by) {
            did.add("revoke " + discordId);
            return revoked;
        }

        @Override
        public boolean unlink(final String discordId, final NordtalUser by) {
            did.add("unlink " + discordId);
            return unlinked;
        }

        @Override
        public List<String> openReferences() {
            return List.of("NT-A1B2C3");
        }

        @Override
        public Settled settle(final String reference, final NordtalUser by) {
            did.add("settle " + reference);
            return settlement;
        }

        @Override
        public boolean reloadMessages() {
            did.add("reload");
            return reloaded;
        }

        @Override
        public List<String> unknownOverrideKeys() {
            return unknownKeys;
        }
    }

    private final FakeBot bot = new FakeBot();

    private FakeUser run(final NordtalCommand<AccessEffects> command,
                         final Map<String, Object> values) {
        final FakeUser user = FakeUser.inGame();
        command.run(user, new Values(command.declaration(), values), bot);
        return user;
    }

    // ------------------------------------------------------------------ the declarations

    @Test
    @DisplayName("every one is admin-only, which four of them were not")
    void theAdminCheckThatWasMissing() {
        // grant-access, revoke-access, access-status and settle ran on Discord's own
        // DefaultMemberPermissions and nothing else - so the network's admin list and the list of
        // people who could grant paid access were two different lists.
        for (final Declaration declaration : AccessCommands.declarations()) {
            assertTrue(declaration.adminOnly(), declaration.name());
        }
    }

    @Test
    @DisplayName("the four that move money or paid time ask first")
    void whatIsIrreversible() {
        assertEquals(Set.of("/access grant", "/access revoke", "/access unlink", "/access settle"),
                AccessCommands.declarations().stream()
                        .filter(Declaration::irreversible)
                        .map(Declaration::name)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    @DisplayName("granting is bounded, which the Discord command was not")
    void grantIsBounded() {
        // It hand-checked "greater than zero" and had no upper bound at all, so a mistyped 3650 was
        // a decade of free access and one keystroke away from 365.
        final var days = AccessCommands.GRANT.arguments().getLast();
        assertEquals(1, days.min());
        assertEquals(365, days.max());
    }

    @Test
    @DisplayName("all of them are reachable in game as well as in Discord")
    void bothDirections() {
        for (final Declaration declaration : AccessCommands.declarations()) {
            assertTrue(declaration.surfaces().containsAll(
                            List.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE)),
                    declaration.name() + " is not on every surface");
        }
    }

    // ------------------------------------------------------------------ the behaviour

    @Test
    @DisplayName("the subject is a Discord account, not a Minecraft one - which is the point")
    void theSubjectIsADiscordAccount() {
        // Written as a PLAYER argument for half an afternoon, which resolves through account_link on
        // both surfaces - so /access grant could not have been used on the person it exists for: a
        // member whose payment arrived outside the normal flow and who has never linked.
        for (final Declaration declaration : AccessCommands.declarations()) {
            declaration.arguments().stream()
                    .filter(argument -> argument.name().equals("member"))
                    .forEach(argument -> assertEquals(
                            eu.nordtal.s2.commands.Argument.Kind.ACCOUNT, argument.kind(),
                            declaration.name() + " resolves its subject through account_link"));
        }
    }

    @Test
    @DisplayName("a member who has left the guild is its own answer, not 'not linked'")
    void aDepartedMember() {
        // The link is still a row and the person is gone. Folding the two would send an admin
        // looking for a link that is right there.
        bot.status = null;
        assertEquals(List.of("access.no-such-member"),
                run(new ShowStatus(), Map.of("member", DISCORD)).keys());
    }

    @Test
    @DisplayName("status prints the whole account, in message keys rather than English")
    void status() {
        bot.status = new AccessEffects.Status("Steve",
                Optional.of(Instant.parse("2026-12-01T00:00:00Z")), true, Locale.GERMAN,
                Optional.of(PLAYER),
                List.of(new AccessEffects.Grant(Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-12-01T00:00:00Z"), "PURCHASE", false)),
                List.of(new AccessEffects.Purchase("NT-A1B2C3", 60, "5.00", "PAID")));

        assertEquals(List.of("access.header", "access.until", "access.donor", "access.language",
                        "access.linked", "access.grants.header", "access.grants.line",
                        "access.purchases.header", "access.purchases.line"),
                run(new ShowStatus(), Map.of("member", DISCORD)).keys());
    }

    @Test
    @DisplayName("an account with nothing on it still says so line by line")
    void anEmptyAccount() {
        bot.status = new AccessEffects.Status("Steve", Optional.empty(), false, Locale.ENGLISH,
                Optional.empty(), List.of(), List.of());

        assertEquals(List.of("access.header", "access.none", "access.donor", "access.language",
                        "access.linked", "access.grants.none", "access.purchases.none"),
                run(new ShowStatus(), Map.of("member", DISCORD)).keys());
    }

    @Test
    @DisplayName("granting names the days and the new end, and records who did it")
    void grant() {
        final FakeUser user = run(new GrantAccess(), Map.of("member", DISCORD, "days", 30));

        assertEquals("access.granted", user.only().key());
        assertEquals(30, user.only().of("days"));
        assertEquals(List.of("grant " + DISCORD + " 30 by tester"), bot.did);
    }

    @Test
    @DisplayName("revoking nothing is a different sentence from revoking something")
    void revokeNothing() {
        // "Revoked 0 grant(s)" is a sentence an admin has to work out. An admin who ran this on the
        // wrong person should be told nothing happened.
        bot.revoked = 0;
        assertEquals(List.of("access.revoked.none"),
                run(new RevokeAccess(), Map.of("member", DISCORD)).keys());

        bot.revoked = 2;
        final FakeUser user = run(new RevokeAccess(), Map.of("member", DISCORD));
        assertEquals("access.revoked", user.only().key());
        assertEquals(2, user.only().of("count"));
    }

    @Test
    @DisplayName("the three ways a settlement ends are three sentences")
    void settle() {
        bot.settlement = new AccessEffects.Settled(AccessEffects.Settlement.UNKNOWN, null, 0, null);
        assertEquals(List.of("access.settle.unknown"),
                run(new SettlePayment(), Map.of("reference", "NT-ZZZZZZ")).keys());

        // Not open is the automatic path having already dealt with it - the opposite problem from a
        // typo, so it names the status rather than sharing a sentence.
        bot.settlement = new AccessEffects.Settled(AccessEffects.Settlement.NOT_OPEN, null, 60,
                "PAID");
        final FakeUser notOpen = run(new SettlePayment(), Map.of("reference", "NT-A1B2C3"));
        assertEquals("access.settle.not-open", notOpen.only().key());
        assertEquals("PAID", notOpen.only().of("status"));

        bot.settlement = new AccessEffects.Settled(AccessEffects.Settlement.BOOKED,
                Instant.parse("2026-12-01T00:00:00Z"), 60, "OPEN");
        final FakeUser booked = run(new SettlePayment(), Map.of("reference", "NT-A1B2C3"));
        assertEquals("access.settle.booked", booked.only().key());
        assertEquals(60, booked.only().of("days"));
    }

    @Test
    @DisplayName("a reload reports the override keys no bundle declares")
    void reload() {
        // An override key nothing declares is stored and never used, which looks exactly like one
        // that works. The moment somebody edits the file is the only time saying so is useful.
        assertEquals(List.of("access.messages.reloaded"),
                run(new ReloadBotMessages(), Map.of()).keys());

        bot.unknownKeys = List.of("smp.admin.reloaded.typo");
        final FakeUser user = run(new ReloadBotMessages(), Map.of());
        assertEquals("access.messages.reloaded-with-unknown", user.only().key());
        assertEquals("smp.admin.reloaded.typo", user.only().of("keys"));

        bot.reloaded = false;
        assertEquals(List.of("access.messages.reload-failed"),
                run(new ReloadBotMessages(), Map.of()).keys());
    }

    @Test
    @DisplayName("a database that does not answer changes nothing and says so")
    void aFailureChangesNothing() {
        bot.failure = new IllegalStateException("connection refused");
        assertEquals(List.of("access.failed"),
                run(new ShowStatus(), Map.of("member", DISCORD)).keys());
        assertEquals(List.of(), bot.did);
    }
}
