package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every decision {@code /smp} takes, without a server.
 *
 * <h2>What could be asked before this existed</h2>
 * Nothing. All six of these lived as Brigadier handlers inside one Paper plugin, so "what does
 * {@code /smp objective complete} say when no milestone is active?" was answerable only by starting
 * a server, loading a world, and arranging for no milestone to be active. The cases below are the
 * ones that were therefore never checked - and one of them was wrong: {@code /smp aura} answered an
 * unlinked target with the message written for a <em>player</em> about their <em>own</em> account.
 */
class SmpCommandsTest {

    private static final UUID SOMEBODY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final FakeSmp smp = new FakeSmp();

    private FakeUser run(final NordtalCommand<SmpEffects> command, final Map<String, Object> values) {
        final FakeUser user = FakeUser.inGame();
        command.run(user, new Values(command.declaration(), values), smp);
        return user;
    }

    // ------------------------------------------------------------------ the declarations

    @Test
    @DisplayName("the three that cannot be undone ask first, and the other three do not")
    void whatIsIrreversible() {
        // A flag on everything that writes is a flag nobody reads. /smp aura is not guarded because
        // applying the negative is an exact undo; /smp reload is not because re-reading a file
        // changes nothing that was not already on disk.
        assertEquals(
                Set.of("/smp farmreset now", "/smp objective complete", "/smp milestone unlock"),
                SmpCommands.declarations().stream()
                        .filter(Declaration::irreversible)
                        .map(Declaration::name)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    @DisplayName("all but /aura are reachable from Discord as well as in game, and all but the two player ones are admin-only")
    void allSevenAreOnBothPlatforms() {
        for (final Declaration declaration : SmpCommands.declarations()) {
            // /smp status is the one /smp command a player may run: three read-only lines about
            // the season (2026-09-06, poliert stage 8). /aura is the second player command this
            // module serves and the one that is not under /smp at all - see SmpCommands#OWN_AURA.
            // Everything that writes stays an admin's.
            final boolean forPlayers =
                    declaration == SmpCommands.STATUS || declaration == SmpCommands.OWN_AURA;
            assertEquals(!forPlayers, declaration.adminOnly(),
                    declaration.name() + " has the wrong admin flag");
            assertTrue(declaration.surfaces().contains(Surface.GAME), declaration.name());
            if (declaration == SmpCommands.OWN_AURA) {
                // It is about the person typing it, so there is nobody for the console to answer
                // about, and in Discord the account is the wrong end of the link: the command takes
                // no argument and would have to guess whose aura was meant.
                assertEquals(Set.of(Surface.GAME), declaration.surfaces(),
                        "/aura is about whoever typed it, and two of the three surfaces have no"
                                + " Minecraft account to be about");
                continue;
            }
            assertTrue(declaration.surfaces().contains(Surface.DISCORD),
                    declaration.name() + " cannot be typed in Discord, which is the whole point of"
                            + " folding /smp into :commands");
            assertTrue(declaration.surfaces().contains(Surface.CONSOLE),
                    declaration.name() + " cannot be run from the console - the gap /hg had");
        }
    }

    // ------------------------------------------------------------------ /aura

    @Test
    @DisplayName("/aura says where you stand and then the board, in that order")
    void auraIsYourOwnLineThenTheBoard() {
        // Your own line first, deliberately: the question somebody types /aura to answer is "where
        // am I", and a list of ten with the answer somewhere inside it is not that.
        final FakeSmp smp = new FakeSmp();
        smp.standing = new SmpEffects.AuraStanding(120, 3, 37, List.of(
                new SmpEffects.AuraLine(1, "Anna", 400, false),
                new SmpEffects.AuraLine(2, "Bert", 200, false),
                new SmpEffects.AuraLine(3, "tester", 120, true)));
        final FakeUser user = FakeUser.inGame();

        new ShowAura().run(user, Values.none(SmpCommands.OWN_AURA), smp);

        assertEquals(List.of("smp.aura.own", "smp.aura.top",
                        "smp.aura.line", "smp.aura.line", "smp.aura.line"), user.keys());
        assertEquals(120, user.replies.getFirst().of("aura"));
        assertEquals(3, user.replies.getFirst().of("rank"));
        assertEquals(37, user.replies.getFirst().of("total"));
        assertEquals(3, user.replies.get(1).of("count"));
    }

    @Test
    @DisplayName("your own line on the board is coloured differently from the rest")
    void yourOwnLineIsMarked() {
        // One key, two tones. A second key with the same words in another colour is two strings to
        // translate, and one of them eventually says something else.
        final FakeSmp smp = new FakeSmp();
        smp.standing = new SmpEffects.AuraStanding(120, 2, 2, List.of(
                new SmpEffects.AuraLine(1, "Anna", 400, false),
                new SmpEffects.AuraLine(2, "tester", 120, true)));
        final FakeUser user = FakeUser.inGame();

        new ShowAura().run(user, Values.none(SmpCommands.OWN_AURA), smp);

        assertEquals(eu.nordtal.s2.common.message.Tone.MUTED, user.replies.get(2).tone());
        assertEquals(eu.nordtal.s2.common.message.Tone.GOOD, user.replies.get(3).tone());
    }

    @Test
    @DisplayName("an empty board says so instead of printing a heading over nothing")
    void anEmptyBoardIsNamed() {
        final FakeSmp smp = new FakeSmp();
        smp.standing = new SmpEffects.AuraStanding(0, 1, 0, List.of());
        final FakeUser user = FakeUser.inGame();

        new ShowAura().run(user, Values.none(SmpCommands.OWN_AURA), smp);

        assertEquals(List.of("smp.aura.own", "smp.aura.empty"), user.keys());
    }

    @Test
    @DisplayName("an account with no Discord link is told, not shown a zero")
    void anUnlinkedAccountIsNamed() {
        // The login gate makes this impossible, and this layer must not assume it: the gate is
        // another process's rule. Showing "0 aura, number 1 of 0" instead would be a wrong answer
        // rather than a refusal.
        final FakeSmp smp = new FakeSmp();
        smp.standing = null;
        final FakeUser user = FakeUser.inGame();

        new ShowAura().run(user, Values.none(SmpCommands.OWN_AURA), smp);

        assertEquals(List.of("smp.aura.unlinked"), user.keys());
    }

    @Test
    @DisplayName("a database that does not answer says so and prints no board")
    void aFailedReadIsNamed() {
        final FakeSmp smp = new FakeSmp();
        smp.failure = new IllegalStateException("no answer");
        final FakeUser user = FakeUser.inGame();

        new ShowAura().run(user, Values.none(SmpCommands.OWN_AURA), smp);

        assertEquals(List.of("smp.aura.failed"), user.keys());
    }

    @Test
    @DisplayName("somebody with no Minecraft account is refused rather than asked about")
    void theConsoleHasNoAura() {
        // Declared on GAME alone, so an adapter refuses the console first. This is the belt: a
        // NordtalUser with no Minecraft account is a shape every command here is written for.
        final FakeSmp smp = new FakeSmp();
        final FakeUser user = FakeUser.console();

        new ShowAura().run(user, Values.none(SmpCommands.OWN_AURA), smp);

        assertEquals(List.of("smp.aura.nobody"), user.keys());
    }

    // ------------------------------------------------------------------ status

    @Test
    @DisplayName("/smp status answers the phase, the milestone with its progress, and who is on")
    void statusIsThreeLines() {
        final FakeSmp smp = new FakeSmp();
        final FakeUser user = FakeUser.inDiscord();
        new ShowStatus().run(user, Values.none(SmpCommands.STATUS), smp);
        assertEquals(List.of("phase.current", "smp.status.milestone", "smp.status.online"),
                user.keys());
        assertEquals("Aufbruch", user.replies.get(1).placeholders().get("milestone"));
        assertEquals(42, user.replies.get(1).placeholders().get("percent"));
        assertEquals(3, user.replies.get(2).placeholders().get("online"));
    }

    @Test
    @DisplayName("the third line is a sentence, and it picks a key rather than a bracketed plural")
    void statusCountsPeopleInSentences() {
        // Three keys and not "{online} player(s)". BundleContinuationTest refuses the bracketed
        // shape in a bundle, so a plural that is not selected here cannot be written down anywhere
        // - which is the point: the selection is the only place the rule can live.
        for (final int[] counts : new int[][]{{0, 0}, {1, 1}, {2, 2}, {57, 2}}) {
            final FakeSmp smp = new FakeSmp();
            smp.status = new SmpEffects.Status("SMP", java.util.Optional.of("Aufbruch"), 42,
                    counts[0]);
            final FakeUser user = FakeUser.inDiscord();
            new ShowStatus().run(user, Values.none(SmpCommands.STATUS), smp);

            final String expected = List.of("smp.status.online.none", "smp.status.online.one",
                    "smp.status.online").get(counts[1]);
            assertEquals(expected, user.keys().get(2),
                    counts[0] + " online should read as " + expected);
        }
    }

    @Test
    @DisplayName("a finished season says so instead of naming a milestone")
    void statusAfterTheLastMilestone() {
        final FakeSmp smp = new FakeSmp();
        smp.status = new SmpEffects.Status("SMP", java.util.Optional.empty(), 0, 12);
        final FakeUser user = FakeUser.inGame();
        new ShowStatus().run(user, Values.none(SmpCommands.STATUS), smp);
        assertEquals(List.of("phase.current", "smp.status.finished", "smp.status.online"), user.keys());
    }

    // ------------------------------------------------------------------ reload

    @Test
    @DisplayName("reload says so, and a refusal names the console rather than swallowing it")
    void reload() {
        assertEquals(List.of("smp.admin.reloaded"), run(new ReloadSmp(), Map.of()).keys());
        assertEquals(List.of("reload"), smp.did);

        smp.failure = new IllegalStateException("milestones.yml is not valid");
        assertEquals(List.of("smp.admin.reload-failed"), run(new ReloadSmp(), Map.of()).keys());
        assertEquals(List.of("/smp reload failed"), smp.warnings);
    }

    @Test
    @DisplayName("a refused track is its own answer, and it names what the file disagrees with")
    void aRefusedTrackNamesTheDisagreement() {
        // Not the same thing as a reload that threw. The file parsed; what it disagrees with is
        // progress the season has already recorded - a renamed milestone key, an objective that
        // changed type, a completed objective whose target moved. The running track is kept, and
        // the person running this is editing milestones.yml on a live season, so the answer has to
        // name the rows rather than say that something went wrong.
        smp.trackRefused = List.of(
                "ancient-debris: has stored progress but is not declared in the file any more.",
                "logs/oak: changed type from STATISTIC to HAND_IN");

        final var user = run(new ReloadSmp(), Map.of());
        assertEquals(List.of("smp.admin.track-refused"), user.keys());
        final String printed = String.valueOf(user.only().of("problems"));
        assertTrue(printed.contains("ancient-debris") && printed.contains("logs/oak"),
                "the answer does not name both problems: " + printed);
        assertEquals(List.of("reload"), smp.did,
                "the reload did not run - the sounds and the wording are re-read regardless");
    }

    // ------------------------------------------------------------------ farm reset

    @Test
    @DisplayName("the farm reset answers before it starts, because there is no moment afterwards")
    void farmResetAnswersFirst() {
        final FakeUser user = run(new ResetFarmWorld(), Map.of());

        assertEquals(List.of("smp.admin.farmreset"), user.keys());
        assertEquals(List.of("farmreset"), smp.did);
    }

    @Test
    @DisplayName("a farm reset that fails half way says the world may be half deleted")
    void farmResetFailure() {
        smp.failure = new IllegalStateException("the folder is locked");
        final FakeUser user = run(new ResetFarmWorld(), Map.of());

        assertEquals(List.of("smp.admin.farmreset", "smp.admin.farmreset-failed"), user.keys());
    }

    // ------------------------------------------------------------------ objectives

    @Test
    @DisplayName("no active milestone and no such objective are different sentences")
    void theTwoRefusalsStayApart() {
        // Folding them into one would leave an admin re-reading the milestone file for a key that
        // is in it.
        assertEquals(List.of("smp.admin.no-active-milestone"),
                run(new CompleteObjective(), Map.of("key", "netherite")).keys());

        smp.activeMilestone = "the-nether";
        assertEquals(List.of("smp.admin.no-such-objective"),
                run(new CompleteObjective(), Map.of("key", "netherite")).keys());
        assertEquals(List.of(), smp.did, "nothing was paid out for an objective that does not exist");
    }

    @Test
    @DisplayName("completing an objective names both it and its milestone")
    void completingAnObjective() {
        smp.activeMilestone = "the-nether";
        smp.objectives = List.of("netherite");

        final FakeUser user = run(new CompleteObjective(), Map.of("key", "netherite"));

        assertEquals("smp.admin.objective-completed", user.only().key());
        assertEquals("netherite", user.only().of("key"));
        assertEquals("the-nether", user.only().of("milestone"));
        assertEquals(List.of("complete the-nether/netherite"), smp.did);
    }

    @Test
    @DisplayName("unlocking a milestone does not check the key first, and says which one it was")
    void unlockingAMilestone() {
        // The engine is the only thing that knows the whole track, active milestones included, so
        // asking twice would be two reads for a command run a handful of times a season.
        final FakeUser user = run(new UnlockMilestone(), Map.of("key", "the-end"));

        assertEquals("smp.admin.milestone-unlocked", user.only().key());
        assertEquals("the-end", user.only().of("key"));
        assertEquals(List.of("unlock the-end"), smp.did);
    }

    // ------------------------------------------------------------------ aura

    @Test
    @DisplayName("an unlinked target is told about, not told off")
    void auraOnAnUnlinkedAccount() {
        // This is the bug the fold found. It used to answer smp.error.no-account-link - "YOUR
        // Minecraft account is not linked" - which is written for a player about their own account
        // and told an admin the wrong thing about the person in front of them.
        smp.names.put(SOMEBODY, "Steve");

        final FakeUser user = run(new ChangeAura(), Map.of("player", SOMEBODY, "delta", -25));

        assertEquals("smp.admin.target-unlinked", user.only().key());
        assertEquals("Steve", user.only().of("player"));
        assertEquals(List.of(), smp.did);
    }

    @Test
    @DisplayName("a correction records who made it")
    void auraRecordsItsAuthor() {
        // An unexplained balance is what the reason column exists to prevent, and an admin's
        // correction is the likeliest one to be questioned.
        smp.names.put(SOMEBODY, "Steve");
        smp.links.put(SOMEBODY, "100000000000000009");

        final FakeUser user = run(new ChangeAura(), Map.of("player", SOMEBODY, "delta", -25));

        assertEquals(List.of("aura 100000000000000009 -25 by tester"), smp.did);
        assertEquals("smp.admin.aura-changed", user.only().key());
        assertEquals("Steve", user.only().of("player"));
        assertEquals(-25, user.only().of("delta"));
    }

    @Test
    @DisplayName("a player this server has never seen is named by UUID rather than not at all")
    void anUnknownNameStillProducesASentence() {
        // Reachable now that the command can arrive from Discord about somebody who is not here.
        final FakeUser user = run(new ChangeAura(), Map.of("player", SOMEBODY, "delta", 1));
        assertEquals(SOMEBODY.toString(), user.only().of("player"));
    }

    // ------------------------------------------------------------------ access

    @Test
    @DisplayName("an unlinked account is said plainly, because it means something else is wrong")
    void accessOnAnUnlinkedAccount() {
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access(null, false, null);

        assertEquals(List.of("smp.access.unlinked"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    @DisplayName("linked, active, and a purchase with a payment link waiting")
    void accessWithAnOpenPayment() {
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", true,
                Instant.parse("2026-10-01T00:00:00Z"));
        smp.payment = FakeSmp.payment(true);

        assertEquals(List.of("smp.access.linked", "smp.access.active", "smp.access.payment"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    @DisplayName("a purchase with no payment link is a different line, and that is the point")
    void aPurchaseThatNeverGotALink() {
        // "Chose 60 days" and "asked for a payment link" are different problems to chase.
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", false, null);
        smp.payment = FakeSmp.payment(false);

        assertEquals(
                List.of("smp.access.linked", "smp.access.never", "smp.access.payment-unstarted"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    @DisplayName("expired access reads differently from access that never existed")
    void expiredIsNotTheSameAsNever() {
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", false,
                Instant.parse("2026-08-01T00:00:00Z"));

        assertEquals(List.of("smp.access.linked", "smp.access.expired", "smp.access.no-payment"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    @DisplayName("a failure reading the payment keeps the access line, which is what was asked for")
    void thePaymentIsASeparateRead() {
        // The two reads behind this command are separate on purpose. Losing the access line because
        // the second query failed would be the wrong trade: the access line is the one an admin
        // came for.
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", true,
                Instant.parse("2026-10-01T00:00:00Z"));
        smp.paymentFailure = new IllegalStateException("the database did not answer");

        assertEquals(List.of("smp.access.linked", "smp.access.active", "smp.access.payment-unknown"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }
}
