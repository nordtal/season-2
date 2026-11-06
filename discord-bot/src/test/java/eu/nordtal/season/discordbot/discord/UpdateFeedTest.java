package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.discord.UpdateFeedFakes.Board;
import eu.nordtal.season.discordbot.discord.UpdateFeedFakes.Rows;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class UpdateFeedTest {

    private static final Instant NOW = UpdateFeedFakes.NOW;

    private final Rows rows = new Rows();
    private final Board board = new Board();
    private final DocumentRenderer documents = StoredDocumentsStandIn.renderer();
    private final List<Runnable> queued = new ArrayList<>(); // passes held as by busy workers

    private final RunCards cards = RunCards.inMemory();
    private final UpdateFeed feed = restarted();

    /** A feed as the bot builds it at start, over the same rows, channel and kept cards. */
    private UpdateFeed restarted() {
        return new UpdateFeed(rows, board, cards, documents, "https://steward.example/", queued::add);
    }

    /** The card the alert of a failed run takes, or {@code null} for none. */
    private static @Nullable String card(final UpdateFeed feed, final long run) {
        final eu.nordtal.season.discordbot.AdminLog.RunCard taken = feed.failedCard(run);
        assertFalse(taken.again(), "the alert of run " + run + " was posted before");
        return taken.messageId();
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.removeFirst().run();
        }
    }

    @Test
    void aPassIsAdmittedBeforeTheHandOverSoABusyPoolQueuesOneAndNotThirty() {
        board.acknowledge = false;
        rows.put(row(1, UpdateStatus.RUNNING, RunStage.STOPPING, null));

        feed.submit();
        feed.submit();
        feed.submit();
        assertEquals(1, queued.size(), "three ticks against a busy pool queued more than one pass");

        queued.getFirst().run();
        assertEquals(1, board.posted.size(), "the one pass that was admitted did not run");

        feed.submit();
        assertEquals(2, queued.size(), "the flag was not released when the pass finished");
    }

    @Test
    void aSignalDuringAPassIsFollowedByOneMorePassSoTheChangeItAnnouncedIsDrawn() {
        rows.put(row(1, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        // The run moves on while the first pass draws it, and the signal of that write arrives during the pass.
        board.duringPost = () -> {
            rows.put(row(2, UpdateStatus.RUNNING, RunStage.STOPPING, null));
            feed.submit();
        };

        feed.submit();
        runQueued();

        assertEquals(2, board.posted.size(), "the row written during the pass waited for the next signal");
    }

    @Test
    void aPoolThatRefusesThePassReleasesTheFlagInsteadOfSwitchingTheFeedOff() {
        final boolean[] refusing = {true};
        final UpdateFeed refused = new UpdateFeed(rows, board, cards, documents, "", task -> {
            if (refusing[0]) {
                throw new RejectedExecutionException("shutting down");
            }
            queued.add(task);
        });
        assertThrows(RejectedExecutionException.class, refused::submit);

        // Without the release in the catch block, one rejection silences the feed for good.
        refusing[0] = false;
        rows.put(row(1, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        refused.submit();
        assertEquals(1, queued.size(), "the flag stayed taken after a rejected submission");
    }

    @Test
    void aCancelledRunIsStillALine() {
        rows.put(row(2, UpdateStatus.CANCELLED, RunStage.RESOLVING, NOW));
        feed.tick();

        assertEquals(1, board.posted.size(), "a cancelled run is still worth a line in the channel");
    }

    private static final Actor OWNER = Actor.person(eu.nordtal.season.common.id.DiscordId.of("300000000000000077"));

    private static UpdateRequest row(
            final long id,
            final UpdateStatus status,
            final @Nullable RunStage stage,
            final @Nullable Instant finished) {
        return new UpdateRequest(id, UpdateKind.UPDATE, status, OWNER, NOW, NOW, null, List.of(), NOW, finished, stage);
    }

    private static UpdateRequest asked(final UpdateKind kind, final RunStage stage, final @Nullable Instant finished) {
        final UpdateStatus status = finished == null ? UpdateStatus.RUNNING : UpdateStatus.DONE;
        return new UpdateRequest(1L, kind, status, OWNER, NOW, NOW, null, List.of(), NOW, finished, stage);
    }

    /** A step of a run, with the artefacts it names. */
    private static RunStep.Row step(
            final RunStep.Kind kind,
            final @Nullable String service,
            final RunStep.@Nullable Outcome outcome,
            final RunStep.@Nullable Reason reason,
            final RunStep.Change... changes) {
        return new RunStep.Row(
                0,
                kind,
                service,
                null,
                null,
                outcome,
                reason,
                null,
                List.of(changes),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    private static DocumentRenderer.Drawn card(
            final UpdateRequest request, final DocumentRenderer documents, final RunStep.Row... steps) {
        return UpdateFeed.card(new RunRecord(request.stage(), List.of(steps)), request, documents, false, null);
    }

    @Test
    void aRunStartedInGameIsPostedAndEditedAsItMoves() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.PLANNED, null));
        feed.tick();

        assertEquals(1, board.posted.size());
        assertTrue(
                lines(board.posted.getFirst().card()).contains("**By** <@300000000000000077>"),
                "who asked is a line of its own, as a mention");

        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        assertEquals(1, board.posted.size(), "the same run, not a second message");
        assertEquals(1, board.edited.size());
        assertEquals("msg-1", board.edited.getFirst().messageId());

        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        assertEquals(2, board.edited.size(), "and the last edit is the answer");

        feed.tick();
        assertEquals(2, board.edited.size(), "a settled run is not followed any more");
    }

    @Test
    void aRunWhoseStageSettlesBeforeItsFinishIsEditedOnceMoreWithHowLongItTook() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.VERIFYING, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.DONE, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW.plusSeconds(150)));
        feed.tick();

        assertEquals(2, board.edited.size(), "the finish is an edit of its own, with nothing else changed");
        assertTrue(
                names(board.edited.getLast().card()).contains("Duration"),
                "the last card says how long the run took: "
                        + lines(board.edited.getLast().card()));
    }

    @Test
    void aBackupNamesTheArchiveOfEachVolumeAndTheDatabaseDumpAndNoServiceReadsNothing() {
        final RunStep.Row dump = saving(RunStep.Kind.DUMP_DATABASE, null, "database", "nordtal-20261106T034501Z.dump");
        final RunStep.Row volume =
                saving(RunStep.Kind.SAVE_VOLUME, "mc-smp", "mc-smp", "mc-smp-20261106T034612Z.tar.zst");
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.DONE, RunStage.DONE, NOW),
                documents,
                dump,
                step(RunStep.Kind.STOP, "smp", RunStep.Outcome.DONE, null),
                volume,
                step(RunStep.Kind.START, "smp", RunStep.Outcome.DONE, null));

        final String shown = field(card, "Services");
        assertTrue(shown.contains("**database** *Dump the database* nordtal-20261106T034501Z.dump, 25.6 MiB"), shown);
        assertTrue(shown.contains("**mc-smp** *Save a volume* mc-smp-20261106T034612Z.tar.zst, 4.2 GiB"), shown);
        assertTrue(shown.contains("**smp** *Start*\n") || shown.endsWith("**smp** *Start*"), shown);
        assertFalse(shown.contains("nothing"), "a service that moved no artefact says nothing about it: " + shown);
    }

    /** A step that saved one archive of the given volume. */
    private static RunStep.Row saving(
            final RunStep.Kind kind, final @Nullable String service, final String volume, final String file) {
        final RunStep.Row bare = step(kind, service, RunStep.Outcome.DONE, null);
        return new RunStep.Row(
                bare.seq(),
                kind,
                service,
                null,
                null,
                RunStep.Outcome.DONE,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(new RunStep.Archive(
                        volume, file, file.endsWith(".dump") ? 26_866_514L : 4_482_615_688L, 6_000L, true, null)),
                List.of());
    }

    @Test
    void anUnchangedRunIsNotReSentBecauseDiscordRateLimitsEdits() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.INSTALLING, null));
        feed.tick();
        feed.tick();
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(
                List.of(),
                board.edited,
                "this polls every two seconds and a run writes a stage at a time; twenty identical"
                        + " edits between two stages would spend the edit budget on nothing");
    }

    @Test
    void aRestartBeginsAtTheNewestRowSoASeasonOfHistoryIsNotRePosted() {
        for (long id = 1; id <= 40; id++) {
            rows.put(row(id, UpdateStatus.DONE, RunStage.DONE, NOW.minus(Duration.ofDays(3))));
        }
        feed.start();
        feed.tick();

        assertEquals(List.of(), board.posted);
    }

    @Test
    void aRunThatEndedWhileTheBotWasDownIsPostedOnceAsAResult() {
        // A row below max(id) is one that the feed never looks at again.
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW.minus(Duration.ofMinutes(4))));
        rows.put(row(2L, UpdateStatus.DONE, RunStage.DONE, NOW.minus(Duration.ofHours(9))));
        feed.start();

        assertEquals(1, board.posted.size(), "and nothing older than the catch-up window");
        feed.tick();
        assertEquals(1, board.posted.size(), "posted once; there is nothing left to edit into it");
    }

    @Test
    void aPostDiscordNeverAcknowledgedIsNotEditedAgainstAMessageIdThatIsNotThere() {
        board.acknowledge = false;
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.RESOLVING, null));
        feed.tick();

        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();

        assertEquals(
                List.of(),
                board.edited,
                "the message id arrives on a JDA thread and may never arrive at all; the row is the"
                        + " record and the card is only a drawing of it");
    }

    @Test
    void aFailedRunsCardIsHandedOnceToTheAlertThatTakesItsPlace() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        rows.put(row(2L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        rows.put(row(3L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        feed.tick();

        assertEquals("msg-1", card(feed, 1L), "failed while it was followed");
        assertEquals("msg-2", card(feed, 2L), "failed before the feed saw it");
        assertTrue(feed.failedCard(1L).again(), "handed over once");
        assertNull(card(feed, 3L), "a run that went well keeps its card");
    }

    @Test
    void aRunFollowedAcrossARestartOfTheBotKeepsItsOneCardAndItIsEditedToTheEnd() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();

        // The update recreates the bot, and the run moves on while it is down.
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.INSTALLING, null));
        final UpdateFeed after = restarted();
        after.start();
        runQueued();
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        after.tick();

        assertEquals(1, board.posted.size(), "the restarted bot posted a second card beside the first");
        assertEquals(
                List.of("msg-1", "msg-1"),
                board.edited.stream().map(Board.Post::messageId).toList());
        assertTrue(
                board.edited.getLast().card().text().contains("Duration"),
                board.edited.getLast().card().text());
    }

    @Test
    void aRestartWithinTheCatchUpWindowEditsTheCardAFinishedRunHasInsteadOfPostingAnother() {
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW.minus(Duration.ofMinutes(4))));
        feed.tick();
        restarted().start();

        assertEquals(1, board.posted.size(), "the catch-up posted a run that already had its card");
    }

    @Test
    void aFailedRunWhoseAlertTookItsCardGetsNoCardBackFromARestart() {
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW.minus(Duration.ofMinutes(4))));
        feed.tick();
        assertEquals("msg-1", card(feed, 1L));
        restarted().start();

        assertEquals(1, board.posted.size(), "the card the alert took came back");
        assertEquals(List.of(), board.edited);
    }

    @Test
    void anAlertAfterARestartTakesThePlaceOfTheCardTheBotPostedBeforeIt() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        final UpdateFeed after = restarted();
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW));

        assertEquals("msg-1", card(after, 1L), "the alert would stand beside the card it replaces");
    }

    @Test
    void aRedeliveredAlertIsToldItsRunsAlertWasPostedAcrossARestartToo() {
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        feed.tick();
        assertEquals("msg-1", card(feed, 1L));

        assertTrue(feed.failedCard(1L).again(), "a second delivery would post a second alert");
        assertTrue(restarted().failedCard(1L).again(), "a second delivery after a restart would post a second alert");
    }

    @Test
    void aRunThatEndsBeforeDiscordAnswersItsPostIsStillRedrawnAndHandedToTheAlert() {
        board.late = true;
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();

        // The run fails, and its signal's pass runs before the card's message id is known.
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        feed.tick();
        board.unanswered.removeFirst().run();
        runQueued();
        assertEquals(1, board.edited.size(), "no signal follows the answer, so the card kept its first stage");
        assertEquals("msg-1", card(feed, 1L), "the alert found no card to take the place of");
    }

    @Test
    void anAlertThatComesBeforeTheFeedHasSeenTheRunEndTakesItsCard() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        assertEquals("msg-1", card(feed, 1L), "the card of a run still followed was not handed over");
        feed.tick();
        assertEquals(List.of(), board.edited, "the card the alert took was drawn again");
        assertTrue(feed.failedCard(1L).again(), "handed over once");
    }

    @Test
    void anAlertThatComesBeforeTheCardIsPostedOrHasItsIdLeavesNoCardBesideIt() {
        board.late = true;
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        feed.tick();
        assertNull(card(feed, 1L), "there is no message id to hand over yet");
        board.unanswered.removeFirst().run();
        runQueued();
        assertEquals(List.of("msg-1"), board.deleted, "the card stayed beside the alert that came first");
        // A run shown only once it failed has its alert before the pass that would post its first card.
        rows.put(row(2L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        assertNull(card(feed, 2L), "there is no card yet");
        feed.tick();
        assertEquals(1, board.posted.size(), "a card was posted after the alert of its run");
    }

    @Test
    void aRunThatIsFollowedFromStartUpIsNotPostedAgainByThePassItsAnswerAsksFor() {
        board.late = true;
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW.minusSeconds(3600)));
        rows.put(row(2L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.start();
        board.unanswered.removeFirst().run();
        runQueued();
        assertEquals(1, board.posted.size(), "the pass after the answer read the history from its start");
    }

    @Test
    void aRunAlreadyFinishedWhenTheFeedFirstSeesItIsPostedOnceAndNotFollowed() {
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        feed.tick();
        assertEquals(1, board.posted.size());
        assertEquals(List.of(), board.edited);
    }

    @Test
    void everyStageCarriesTheSameColourAndItsOutcomeAsAnEmoji() {
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        rows.steps.put(
                1L,
                List.of(
                        step(RunStep.Kind.VERIFY, "smp", RunStep.Outcome.DONE, null),
                        step(RunStep.Kind.STOP, "limbo", RunStep.Outcome.SKIPPED, RunStep.Reason.HELD)));
        rows.put(row(2L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        rows.steps.put(
                2L, List.of(step(RunStep.Kind.VERIFY, "smp", RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY)));
        feed.tick();

        final DocumentRenderer.Drawn good = board.posted.get(0).card();
        final DocumentRenderer.Drawn bad = board.posted.get(1).card();
        assertEquals(accent(good), accent(bad), "a finished and a failed run carry the same line");
        assertTrue(title(good).startsWith("✅"), title(good));
        assertTrue(title(bad).startsWith("🛑"), title(bad));
        final String goodLines = lines(good);
        assertTrue(goodLines.contains("✅ **smp**"), goodLines);
        assertTrue(goodLines.contains("➖ **limbo**"), goodLines);
        for (final String symbol : List.of("✔", "✖", "○", "◑", "\u2013")) {
            assertTrue(!goodLines.contains(symbol), "a text symbol beside emojis: " + symbol);
        }
    }

    @Test
    void aServicesLineIsItsWorstStepItsLatestStepAndTheArtefactsOfItsLatestStepThatHasAny() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.DONE, RunStage.DONE, NOW),
                documents,
                step(
                        RunStep.Kind.INSTALL,
                        "smp",
                        RunStep.Outcome.DONE,
                        null,
                        new RunStep.Change("paper", "133", "134", null),
                        new RunStep.Change("image", null, null, RunStep.Image.PULLED)),
                step(RunStep.Kind.VERIFY, "smp", RunStep.Outcome.DONE, null),
                step(
                        RunStep.Kind.INSTALL,
                        "proxy",
                        RunStep.Outcome.DONE,
                        null,
                        new RunStep.Change(
                                "resource-pack", "f8fb6640b394ffeea812d02b8c4b3d4cdfe956a9", "0.24.0", null)),
                step(RunStep.Kind.VERIFY, "proxy", RunStep.Outcome.WARNING, RunStep.Reason.NOT_HEALTHY));

        final String shown = field(card, "Services");
        assertTrue(shown.contains("✅ **smp** *Verify* paper 133 → 134"), shown);
        assertTrue(shown.contains("image pulled"), shown);
        assertTrue(
                shown.contains("⚠️ **proxy** *Verify* resource-pack f8fb6640 → 0.24.0"),
                "a pack's hash is short: " + shown);
        assertFalse(shown.contains("run."), "no key is ever shown in place of its words: " + shown);
    }

    @Test
    void aStepStillRunningMarksItsServiceAsWorking() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null),
                documents,
                step(RunStep.Kind.STOP, "smp", null, null));

        assertTrue(field(card, "Services").startsWith("🔄 **smp** *Stop*"), field(card, "Services"));
    }

    @Test
    void aSuccessfulRunShowsNoReasonsWhateverItsStepsSay() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.DONE, RunStage.DONE, NOW),
                documents,
                step(RunStep.Kind.STANDBY_UP, "limbo-standby", RunStep.Outcome.DONE, null),
                step(RunStep.Kind.PLAYERS_MOVED, null, RunStep.Outcome.WARNING, RunStep.Reason.PLAYERS_UNKNOWN),
                step(RunStep.Kind.STOP, "smp", RunStep.Outcome.WARNING, RunStep.Reason.UNVERIFIED_STOP),
                step(RunStep.Kind.OFFSITE_COPY, null, RunStep.Outcome.SKIPPED, RunStep.Reason.NO_REPOSITORY));

        assertEquals(List.of("Duration", "Services"), names(card), "a run that went well is its lines");
    }

    @Test
    void aMissingSecretIsSaidUnderItsServiceEvenInARunThatWentWell() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.DONE, RunStage.DONE, NOW),
                documents,
                step(
                        RunStep.Kind.RESOLVE,
                        "steward",
                        RunStep.Outcome.WARNING,
                        RunStep.Reason.LOCAL_BUILD,
                        new RunStep.Change("steward", "0.64.0", "0.65.0", null)),
                step(
                        RunStep.Kind.RESOLVE,
                        "steward",
                        RunStep.Outcome.WARNING,
                        RunStep.Reason.MISSING_SECRET,
                        new RunStep.Change("NORDTAL_STEWARD_WEB_TOTP_KEY", null, null, null)));

        assertEquals(List.of("Duration", "Services", "Why"), names(card));
        final String shown = field(card, "Services");
        assertTrue(shown.contains("steward 0.64.0 → 0.65.0"), "the line is what it moves: " + shown);
        assertFalse(shown.contains("TOTP"), shown);
        assertEquals(
                "**steward** a secret is missing, ./nordtal.sh update on the host adds it",
                field(card, "Why"),
                "only the host adds it, so the card says how; the local build stays a mark");
    }

    @Test
    void aFailedRunShowsOnlyWhatFailedItEachUnderItsService() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW),
                documents,
                step(RunStep.Kind.STANDBY_UP, "limbo-standby", RunStep.Outcome.DONE, null),
                step(RunStep.Kind.PLAYERS_MOVED, null, RunStep.Outcome.WARNING, RunStep.Reason.PLAYERS_UNKNOWN),
                step(RunStep.Kind.STOP, "smp", RunStep.Outcome.FAILED, RunStep.Reason.REFUSED),
                step(RunStep.Kind.STOP, "hunger_games", RunStep.Outcome.FAILED, RunStep.Reason.NO_CONTAINER),
                step(RunStep.Kind.CLEANUP, null, RunStep.Outcome.FAILED, RunStep.Reason.UNEXPECTED));

        assertEquals(List.of("Duration", "Services", "Why"), names(card));
        assertEquals("""
                **smp** refused, the run log has the answer
                **hunger\\_games** no container
                steward-agent failed, its log says how""", field(card, "Why"), "the failed steps in order; nothing that went right and no warning");
    }

    @Test
    void aStoppedRunIsItsTitleAndARunInFlightSaysWhyOnlyOnceItHasFailed() {
        final DocumentRenderer.Drawn stopped = card(
                row(1L, UpdateStatus.CANCELLED, RunStage.CANCELLED, NOW),
                documents,
                step(RunStep.Kind.COUNTDOWN, null, RunStep.Outcome.SKIPPED, RunStep.Reason.CANCELLED));
        final DocumentRenderer.Drawn running = card(
                row(2L, UpdateStatus.RUNNING, RunStage.VERIFYING, null),
                documents,
                step(RunStep.Kind.STOP, "smp", RunStep.Outcome.FAILED, RunStep.Reason.REFUSED),
                step(RunStep.Kind.STANDBY_UP, "limbo-standby", RunStep.Outcome.FAILED, RunStep.Reason.NO_CONTAINER));

        assertFalse(names(stopped).contains("Why"), names(stopped).toString());
        assertFalse(names(running).contains("Why"), "the card is edited once the run settles: " + names(running));
    }

    @Test
    void theTitleNamesTheRunsKindAtEachStage() {
        assertEquals("✅ Backup finished", title(UpdateKind.BACKUP, RunStage.DONE));
        assertEquals("🛑 The backup failed", title(UpdateKind.BACKUP, RunStage.FAILED));
        assertEquals("✅ Update finished", title(UpdateKind.UPDATE, RunStage.DONE));
        assertEquals("⏳ Restarting shortly", title(UpdateKind.RESTART, RunStage.COUNTDOWN));
        assertEquals("🔄 Stopping the servers for the take-down", title(UpdateKind.DOWN, RunStage.STOPPING));
        assertEquals("✅ Nothing to start", title(UpdateKind.START, RunStage.NOTHING_TO_DO));
        assertEquals("⏹️ Plugin removal cancelled", title(UpdateKind.REMOVE_PLUGIN, RunStage.CANCELLED));
        assertEquals("✅ Mode switch finished", title(UpdateKind.SWITCH_MODE, RunStage.DONE));
        for (final UpdateKind kind : UpdateKind.values()) {
            for (final RunStage stage : RunStage.values()) {
                final String shown = title(kind, stage);
                assertFalse(shown.contains("{") || shown.contains("run."), "unrendered: " + shown);
            }
        }
    }

    @Test
    void theKindIsInTheTitleAndNotAgainAsAField() {
        rows.put(asked(UpdateKind.BACKUP, RunStage.DONE, NOW));
        feed.tick();

        assertEquals(List.of("By", "Duration"), names(board.posted.getFirst().card()));
    }

    @Test
    void theCardLinksToTheRunsPageInStewardWhileItMovesAndOnceItIsDone() {
        rows.put(row(7L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        rows.put(row(7L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();

        final Button posted =
                java.util.Objects.requireNonNull(link(board.posted.getFirst().card()));
        assertEquals("https://steward.example/operations/updates/7", posted.getUrl());
        assertEquals("Open in Steward", posted.getLabel());
        assertEquals(
                posted.getUrl(),
                java.util.Objects.requireNonNull(link(board.edited.getFirst().card()))
                        .getUrl(),
                "an edit keeps the link");
    }

    @Test
    void withoutStewardsAddressTheCardCarriesNoLink() {
        final UpdateFeed unlinked = new UpdateFeed(rows, board, cards, documents, "", queued::add);
        rows.put(row(7L, UpdateStatus.DONE, RunStage.DONE, NOW));
        unlinked.tick();

        assertEquals(null, link(board.posted.getFirst().card()));
    }

    @Test
    void aStartUpInstallThatWentWellIsNotPosted() {
        feed.start();
        rows.put(install(1L, UpdateStatus.DONE, RunStage.DONE));
        feed.tick();

        assertEquals(List.of(), board.posted, "the admins hear of a start-up install only when it failed");
    }

    @Test
    void aStartUpInstallThatFailedIsPostedUnderItsOwnTitle() {
        feed.start();
        rows.put(install(1L, UpdateStatus.FAILED, RunStage.FAILED));
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(
                "🛑 The start-up install failed", title(board.posted.getFirst().card()));
    }

    @Test
    void aStartUpInstallThatWentWellWhileTheBotWasDownIsNotCaughtUp() {
        rows.put(install(1L, UpdateStatus.DONE, RunStage.DONE));
        rows.put(install(2L, UpdateStatus.FAILED, RunStage.FAILED));
        feed.start();

        assertEquals(1, board.posted.size(), "only the failed one is caught up");
    }

    @Test
    void anUpdateThatFoundNothingToDoIsNotPosted() {
        feed.start();
        rows.put(row(1L, UpdateStatus.PENDING, null, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.RESOLVING, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.DONE, RunStage.NOTHING_TO_DO, NOW));
        feed.tick();
        feed.tick();

        assertEquals(List.of(), board.posted, "the check after a real update says nothing new");
    }

    @Test
    void anUpdateThatFoundNothingToDoWhileTheBotWasDownIsNotCaughtUp() {
        rows.put(row(1L, UpdateStatus.DONE, RunStage.NOTHING_TO_DO, NOW));
        feed.start();

        assertEquals(List.of(), board.posted);
    }

    @Test
    void anUpdateIsPostedOnceItHasAPlanAndFollowedFromThere() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.RESOLVING, null));
        feed.tick();
        assertEquals(List.of(), board.posted, "while it resolves, it may still find nothing to do");

        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.COUNTDOWN, null));
        feed.tick();
        assertEquals(1, board.posted.size());

        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        assertEquals(1, board.posted.size(), "the same run, not a second message");
        assertEquals(1, board.edited.size());
    }

    @Test
    void aRestartThatFoundNothingToDoKeepsItsCardBecauseSomeoneAskedForIt() {
        rows.put(asked(UpdateKind.RESTART, RunStage.NOTHING_TO_DO, NOW));
        feed.tick();

        assertEquals(1, board.posted.size());
    }

    @Test
    void aModeSwitchIsPostedFromItsFirstStageBecauseItMovesEveryPlayer() {
        rows.put(asked(UpdateKind.SWITCH_MODE, RunStage.RESOLVING, null));
        feed.tick();

        assertEquals(1, board.posted.size());
    }

    private static UpdateRequest install(final long id, final UpdateStatus status, final RunStage stage) {
        return new UpdateRequest(
                id, UpdateKind.INSTALL, status, Actor.STEWARD, NOW, NOW, null, List.of(), NOW, NOW, stage);
    }

    private String title(final UpdateKind kind, final RunStage stage) {
        return title(card(asked(kind, stage, null), documents));
    }

    private static String title(final DocumentRenderer.Drawn card) {
        return card.text().lines().findFirst().orElseThrow().replaceFirst("^## ", "");
    }

    /** Returns the sections of the card in order: the by and duration lines, then each heading. */
    private static List<String> names(final DocumentRenderer.Drawn card) {
        return card.text()
                .lines()
                .map(line -> line.startsWith("**By**")
                        ? "By"
                        : line.startsWith("**Duration**")
                                ? "Duration"
                                : line.startsWith("### ") ? line.substring(4) : null)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String field(final DocumentRenderer.Drawn card, final String name) {
        final List<String> all = card.text().lines().toList();
        final int heading = all.indexOf("### " + name);
        if (heading < 0) {
            throw new AssertionError("no heading " + name + " in " + names(card));
        }
        return all.subList(heading + 1, all.size()).stream()
                .takeWhile(line -> !line.startsWith("### "))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String lines(final DocumentRenderer.Drawn card) {
        return card.text();
    }

    private static @Nullable Integer accent(final DocumentRenderer.Drawn card) {
        return MessageComponentTree.of(card.components())
                .findAll(net.dv8tion.jda.api.components.container.Container.class)
                .getFirst()
                .getAccentColorRaw();
    }

    private static @Nullable Button link(final DocumentRenderer.Drawn card) {
        return MessageComponentTree.of(card.components()).findAll(Button.class).stream()
                .filter(button -> button.getStyle() == ButtonStyle.LINK)
                .findFirst()
                .orElse(null);
    }
}
