package eu.nordtal.s2.commands.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.announce.AnnounceCommands;
import eu.nordtal.s2.commands.announce.AnnounceEffects;
import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.NewCommandRequest;
import eu.nordtal.s2.common.message.Messages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code /announce} is {@code adminOnly}; the flag itself is not what this test is about.
 *
 * {@code CatalogueTest} already asserts which declarations are not admin-only, and a list of names
 * is exactly as true as somebody's belief about what the list means. The claim being made here is a
 * claim about two behaviours, and they pull in opposite directions: the SMP must be unaffected - it
 * writes {@code source = 'CONSOLE'}, which {@link CommandInbox.AdminCheck#of} lets through by
 * definition, so every milestone announcement still posts even when the admin roster is empty -
 * and a row written from the web interface by somebody whose admin role has since been taken away
 * must not still post into an announcement channel.
 *
 * The second is the whole point of the change and it is invisible at the declaration: whether
 * {@code adminOnly} is even consulted is decided in {@code CommandInbox#handle}, at the moment the
 * row is claimed, which is minutes after the browser submitted it. Flipping the flag back to
 * {@code false} leaves {@code CatalogueTest} red - and leaves this class red for the reason that
 * actually matters, which is a WEB row running unauthorised.
 *
 * The real {@link AnnounceCommands#ANNOUNCE} and the real {@code AdminCheck.of} are used
 * throughout. {@code CommandInboxTest} builds its inboxes with {@code request -> admin} on purpose,
 * so no test in this module put the two real halves together before.
 */
class AnnounceAuthorisationTest {

    private static final Messages MESSAGES = Messages.load(
            AnnounceAuthorisationTest.class.getClassLoader(), "messages/commands", Locale.ENGLISH, Locale.GERMAN);

    private static final String ADMIN = "100000000000000001";
    private static final String NO_LONGER_ADMIN = "200000000000000002";

    /** What the bot would do: remember what it was asked to post, and say it found a channel. */
    private record Posted(List<String> lines) implements AnnounceEffects {

        @Override
        public boolean post(final String languageTag, final String text) {
            lines.add(languageTag + ": " + text);
            return true;
        }

        @Override
        public void async(final Runnable work) {
            work.run();
        }

        @Override
        public void warn(final String what, final Throwable failure) {}
    }

    private final FakeRequests requests = new FakeRequests();
    private final Posted effects = new Posted(new ArrayList<>());

    /** The bot's inbox, with the roster it would read out of the database on every claim. */
    private CommandInbox inboxWhereTheAdminsAre(final Set<String> admins) {
        final CommandInbox inbox = new CommandInbox(
                Target.BOT,
                requests,
                MESSAGES,
                CommandInbox.AdminCheck.of(() -> admins, Set::of),
                (message, cause) -> {});
        AnnounceCommands.all().forEach(command -> inbox.register(command, effects));
        return inbox;
    }

    private long row(final String source, final String discordId, final String text) {
        return requests.submit(new NewCommandRequest(
                AnnounceCommands.ANNOUNCE.target().name(),
                String.join(" ", AnnounceCommands.ANNOUNCE.path()),
                RequestArguments.encode(
                        AnnounceCommands.ANNOUNCE,
                        new Values(AnnounceCommands.ANNOUNCE, Map.of("language", "de", "text", text))),
                source,
                "smp".equals(source) ? "smp" : "Till (" + discordId + ")",
                Optional.ofNullable(discordId),
                Optional.empty(),
                "en",
                Instant.now().plusSeconds(3600)));
    }

    @Test
    void theSmpsOwnAnnouncementStillPostsWithNobodyAtAllOnTheAdminRoster() {
        // Announcer#row writes source CONSOLE, requested_by "smp" and no identity of any kind.
        final long id = row("CONSOLE", null, "Der Aufbruch ist geschafft.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of()).drain());

        assertEquals(List.of("de: Der Aufbruch ist geschafft."), effects.lines());
        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
        assertTrue(requests.resultOf(id).contains("announcement channel"), requests.resultOf(id));
    }

    @Test
    void anAnnouncementAskedForInTheBrowserByARevokedAdminIsNotPosted() {
        // The case the change was made for, and the one nobody can rehearse against real systems: the role is taken.
        final long id = row("WEB", NO_LONGER_ADMIN, "Server geht gleich aus.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of(ADMIN)).drain());

        assertEquals(List.of(), effects.lines(), "a revoked admin's line was posted into an announcement channel");
        // DONE and not FAILED: the command was answered, and the answer is no. A FAILED row would read.
        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.not-admin"), requests.resultOf(id));
    }

    @Test
    void anAnnouncementAskedForInTheBrowserByAnAdminIsPosted() {
        // The other side: without it, the tests above are also satisfied by an inbox that admits everyone.
        final long id = row("WEB", ADMIN, "Wartung um 20 Uhr.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of(ADMIN)).drain());

        assertEquals(List.of("de: Wartung um 20 Uhr."), effects.lines());
        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
    }

    @Test
    void aRowThatCarriesNoIdentityAtAllIsRefusedUnlessItIsTheConsole() {
        // GAME rows may legitimately have no Discord id - limbo writes exactly those.
        final long id = row("GAME", null, "Hallo aus dem Nichts.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of(ADMIN)).drain());

        assertEquals(List.of(), effects.lines());
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.not-admin"), requests.resultOf(id));
    }

    @Test
    void theDeclarationTheTwoBehavioursAboveComeFromIsTheOneInTheCatalogue() {
        // Not a trivial restatement of the flag: it is the join between this class and CatalogueTest.
        assertTrue(AnnounceCommands.ANNOUNCE.adminOnly());
        assertTrue(eu.nordtal.s2.commands.Catalogue.all().contains(AnnounceCommands.ANNOUNCE));
    }
}
