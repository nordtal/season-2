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
 * {@code /announce} with the real {@code AdminCheck}: the SMP's CONSOLE rows post, a revoked admin's WEB row does not.
 *
 * The check runs when the row is claimed, minutes after it was written, so the declaration alone cannot show it.
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
        // Announcer#row writes source CONSOLE, requested_by "smp" and no identity.
        final long id = row("CONSOLE", null, "Der Aufbruch ist geschafft.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of()).drain());

        assertEquals(List.of("de: Der Aufbruch ist geschafft."), effects.lines());
        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
        assertTrue(requests.resultOf(id).contains("announcement channel"), requests.resultOf(id));
    }

    @Test
    void anAnnouncementAskedForInTheBrowserByARevokedAdminIsNotPosted() {
        // The role was taken away after the row was written.
        final long id = row("WEB", NO_LONGER_ADMIN, "Server geht gleich aus.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of(ADMIN)).drain());

        assertEquals(List.of(), effects.lines(), "a revoked admin's line was posted into an announcement channel");
        // DONE and not FAILED: the command was answered, and the answer is no.
        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.not-admin"), requests.resultOf(id));
    }

    @Test
    void anAnnouncementAskedForInTheBrowserByAnAdminIsPosted() {
        // Without this, an inbox that admits everyone would pass the tests above.
        final long id = row("WEB", ADMIN, "Wartung um 20 Uhr.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of(ADMIN)).drain());

        assertEquals(List.of("de: Wartung um 20 Uhr."), effects.lines());
        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
    }

    @Test
    void aRowThatCarriesNoIdentityAtAllIsRefusedUnlessItIsTheConsole() {
        // GAME rows may have no Discord id; limbo writes exactly those.
        final long id = row("GAME", null, "Hallo aus dem Nichts.");

        assertEquals(1, inboxWhereTheAdminsAre(Set.of(ADMIN)).drain());

        assertEquals(List.of(), effects.lines());
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.not-admin"), requests.resultOf(id));
    }

    @Test
    void theDeclarationTheTwoBehavioursAboveComeFromIsTheOneInTheCatalogue() {
        // This joins this class to CatalogueTest.
        assertTrue(AnnounceCommands.ANNOUNCE.adminOnly());
        assertTrue(eu.nordtal.s2.commands.Catalogue.all().contains(AnnounceCommands.ANNOUNCE));
    }
}
