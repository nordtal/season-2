package eu.nordtal.season.database.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** An admin's area editor: Steward sends the shapes, and smp answers each step with the draft as JSON. */
class AreaDraftIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("400000000000000001"));
    private static final PlayerId ALEX = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final Schedule MINUTE = Schedule.within(Duration.ofMinutes(1));
    private static final AreaShape SPAWN = new AreaShape(
            "Spawn",
            List.of(new AreaShape.Corner(0, 0), new AreaShape.Corner(10, 0), new AreaShape.Corner(10, 10)),
            0,
            0);

    private static Inbox<SmpRequest> steward;
    private static Inbox<SmpRequest> smp;

    @BeforeAll
    static void freshDatabase() {
        final TestDatabase database = TestDatabase.fresh();
        steward = Inbox.over(database.dataSourceAs(DatabaseRole.STEWARD), SmpRequest.TABLE);
        smp = Inbox.over(database.dataSourceAs(DatabaseRole.SMP), SmpRequest.TABLE);
    }

    @Test
    void theShapesStewardSendsAreWhatSmpReads() {
        final SmpRequest.EditAreas edit = new SmpRequest.EditAreas(ALEX, "nordtal", false, List.of(SPAWN));
        final Request<SmpRequest> asked = steward.submit(edit, ADMIN, MINUTE);

        assertEquals(edit, smp.find(asked.id()).orElseThrow().payload());
        assertEquals(1, smp.drain(request -> Outcome.done(null)));
        assertNull(steward.find(asked.id()).orElseThrow().outcome());
    }

    @Test
    void theDraftSmpAnswersIsWhatStewardReadsBack() {
        final Request<SmpRequest> asked =
                steward.submit(new SmpRequest.AreaDraft(ALEX, SmpRequest.AreaDraft.Step.READ, ""), ADMIN, MINUTE);
        final SmpRequest.AreaDraft.Answer draft = new SmpRequest.AreaDraft.Answer("nordtal", List.of(SPAWN));

        assertEquals(1, smp.drain(request -> {
            assertTrue(smp.settle(request.id(), Outcome.done(draft)).isPresent());
            return Outcome.open();
        }));

        final Request<SmpRequest> read = steward.find(asked.id()).orElseThrow();
        assertEquals(InboxStatus.DONE, read.status());
        assertEquals(Optional.of(draft), read.outcome(SmpRequest.AreaDraft.Answer.class));
    }

    @Test
    void aStepWithoutADraftIsRefusedWithItsReason() {
        final Request<SmpRequest> asked =
                steward.submit(new SmpRequest.AreaDraft(ALEX, SmpRequest.AreaDraft.Step.CLOSE, ""), ADMIN, MINUTE);

        assertEquals(1, smp.drain(request -> Outcome.refused(ServerRefusal.NO_AREA_DRAFT.with())));

        assertEquals(
                ServerRefusal.NO_AREA_DRAFT,
                steward.find(asked.id()).orElseThrow().refusal().orElseThrow().reason());
    }

    @Test
    void stewardWithdrawsASmpRequestWithoutWritingTheOutcomeColumn() {
        final Request<SmpRequest> asked =
                steward.submit(new SmpRequest.AreaDraft(ALEX, SmpRequest.AreaDraft.Step.READ, ""), ADMIN, MINUTE);

        final Request<SmpRequest> withdrawn = steward.withdraw(asked.id()).orElseThrow();
        assertEquals(InboxStatus.CANCELLED, withdrawn.status());
    }
}
