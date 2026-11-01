package eu.nordtal.season.database.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** An item an admin takes in from the game: a request smp leaves open, and Steward withdraws or reads back. */
class TakeInIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("400000000000000001"));
    private static final PlayerId ALEX = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final Schedule MINUTE = Schedule.within(Duration.ofMinutes(1));

    private static Inbox<SmpRequest> steward;
    private static Inbox<SmpRequest> smp;

    @BeforeAll
    static void freshDatabase() {
        final TestDatabase database = TestDatabase.fresh();
        steward = Inbox.over(database.dataSourceAs(DatabaseRole.STEWARD), SmpRequest.TABLE);
        smp = Inbox.over(database.dataSourceAs(DatabaseRole.SMP), SmpRequest.TABLE);
    }

    @Test
    void aRequestTheServerLeftOpenStaysRunningUntilStewardWithdrawsIt() {
        final Request<SmpRequest> asked = steward.submit(new SmpRequest.TakeInItem(ALEX), ADMIN, MINUTE);

        assertEquals(1, smp.drain(request -> Outcome.open()));
        assertEquals(InboxStatus.RUNNING, steward.find(asked.id()).orElseThrow().status());

        assertEquals(
                InboxStatus.CANCELLED,
                steward.withdraw(asked.id()).orElseThrow().status());
        assertTrue(
                smp.settle(asked.id(), Outcome.done(SmpRequest.TakeInItem.Answer.none()))
                        .isEmpty(),
                "settled a withdrawn request");
        assertTrue(steward.withdraw(asked.id()).isEmpty(), "withdrew a request twice");
    }

    @Test
    void aPendingRequestIsWithdrawnTooAndTheServerNeverClaimsIt() {
        final Request<SmpRequest> asked = steward.submit(new SmpRequest.TakeInItem(ALEX), ADMIN, MINUTE);

        assertEquals(
                InboxStatus.CANCELLED,
                steward.withdraw(asked.id()).orElseThrow().status());
        assertEquals(0, smp.drain(request -> Outcome.open()));
    }

    @Test
    void theItemPutInIsTheAnswerStewardReadsBack() {
        final Request<SmpRequest> asked = steward.submit(new SmpRequest.TakeInItem(ALEX), ADMIN, MINUTE);
        smp.drain(request -> Outcome.open());
        final SmpRequest.TakeInItem.Answer item = new SmpRequest.TakeInItem.Answer("minecraft:diamond_sword", "AAEC");

        assertTrue(smp.settle(asked.id(), Outcome.done(item)).isPresent());

        final Request<SmpRequest> read = steward.find(asked.id()).orElseThrow();
        assertEquals(InboxStatus.DONE, read.status());
        assertEquals(Optional.of(item), read.answer(SmpRequest.TakeInItem.Answer.class));
        assertEquals(new SmpRequest.TakeInItem(ALEX), read.payload());
    }

    @Test
    void aWindowClosedEmptyAnswersWithoutAnItem() {
        final Request<SmpRequest> asked = steward.submit(new SmpRequest.TakeInItem(ALEX), ADMIN, MINUTE);
        smp.drain(request -> Outcome.open());

        assertTrue(smp.settle(asked.id(), Outcome.done(SmpRequest.TakeInItem.Answer.none()))
                .isPresent());

        assertEquals(
                Optional.of(SmpRequest.TakeInItem.Answer.none()),
                steward.find(asked.id()).orElseThrow().answer(SmpRequest.TakeInItem.Answer.class));
    }
}
