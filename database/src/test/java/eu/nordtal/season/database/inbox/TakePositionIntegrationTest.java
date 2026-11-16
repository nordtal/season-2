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

/** A position an admin takes in from the game: smp answers where the player stands, and Steward reads it back. */
class TakePositionIntegrationTest {

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
    void thePlaceTheServerAnswersIsWhatStewardReadsBack() {
        final SmpRequest.TakePosition take = new SmpRequest.TakePosition(ALEX, true);
        final Request<SmpRequest> asked = steward.submit(take, ADMIN, MINUTE);
        assertEquals(take, smp.find(asked.id()).orElseThrow().payload());
        final SmpRequest.TakePosition.Answer place =
                new SmpRequest.TakePosition.Answer("nordtal", 106.5, 64.0, -88.25, 90.5f, -12.5f);

        assertEquals(1, smp.drain(request -> {
            assertTrue(smp.settle(request.id(), Outcome.done(place)).isPresent());
            return Outcome.open();
        }));

        final Request<SmpRequest> read = steward.find(asked.id()).orElseThrow();
        assertEquals(InboxStatus.DONE, read.status());
        assertEquals(Optional.of(place), read.answer(SmpRequest.TakePosition.Answer.class));
    }

    @Test
    void aPlayerWhoSeesNoBlockIsRefusedWithItsReason() {
        final Request<SmpRequest> asked = steward.submit(new SmpRequest.TakePosition(ALEX, true), ADMIN, MINUTE);

        assertEquals(1, smp.drain(request -> Outcome.refused(ServerRefusal.NO_BLOCK_IN_SIGHT.with())));

        final Request<SmpRequest> read = steward.find(asked.id()).orElseThrow();
        assertEquals(InboxStatus.REFUSED, read.status());
        assertEquals(ServerRefusal.NO_BLOCK_IN_SIGHT, read.refusal().orElseThrow().reason());
    }

    @Test
    void aTakeStillWaitingIsWithdrawnAndNeverAnswered() {
        final Request<SmpRequest> asked = steward.submit(new SmpRequest.TakePosition(ALEX, false), ADMIN, MINUTE);

        assertEquals(InboxStatus.CANCELLED, steward.withdraw(asked.id()).orElseThrow().status());
        assertEquals(0, smp.drain(request -> Outcome.open()));
    }
}
