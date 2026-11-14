package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WheelFacesTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private final List<WheelPrizeSpec> pool = pool();
    private final WheelFaces faces = new WheelFaces(new Random(7));

    private static List<WheelPrizeSpec> pool() {
        final List<WheelPrizeSpec> out = new ArrayList<>();
        for (int i = 0; i < WheelPlan.PLACES; i++) {
            final int amount = i + 1;
            out.add(new WheelPrizeSpec() {
                @Override
                public int weight() {
                    return 1;
                }

                @Override
                public String item() {
                    return "minecraft:dirt";
                }

                @Override
                public int amount() {
                    return amount;
                }
            });
        }
        return out;
    }

    @Test
    void aWheelKeepsItsPrizesUntilATurnOnThemEnds() {
        final WheelFaces.Next first = faces.next(PLAYER, pool);
        assertTrue(first.drawn());
        final WheelFaces.Next again = faces.next(PLAYER, pool);
        assertFalse(again.drawn());
        assertSame(first.face(), again.face());
    }

    @Test
    void theNextOpenOrSpinAfterATurnDrawsNewPrizesOnce() {
        final WheelPlan.Face face = faces.next(PLAYER, pool).face();
        final WheelPlan.Face after = WheelPlan.turning(face, 5).after();
        faces.turning(PLAYER, after);
        assertSame(after, faces.next(PLAYER, pool).face(), "the turned prizes show until the turn ends");
        faces.turning(PLAYER, after);
        faces.ended(PLAYER);
        final WheelFaces.Next fresh = faces.next(PLAYER, pool);
        assertTrue(fresh.drawn());
        final WheelFaces.Next kept = faces.next(PLAYER, pool);
        assertFalse(kept.drawn());
        assertSame(fresh.face(), kept.face());
    }

    @Test
    void aSpinThatGoesBackLeavesTheWheelAsBefore() {
        final WheelPlan.Face face = faces.next(PLAYER, pool).face();
        final WheelFaces.Before before =
                faces.turning(PLAYER, WheelPlan.turning(face, 3).after());
        faces.back(PLAYER, before);
        final WheelFaces.Next next = faces.next(PLAYER, pool);
        assertFalse(next.drawn());
        assertEquals(face, next.face());
    }

    @Test
    void aSpinAfterATurnThatGoesBackStillDrawsNewPrizesNextTime() {
        final WheelPlan.Face face = faces.next(PLAYER, pool).face();
        faces.turning(PLAYER, WheelPlan.turning(face, 3).after());
        faces.ended(PLAYER);
        final WheelFaces.Next spin = faces.peek(PLAYER, pool);
        assertTrue(spin.drawn(), "a spin after a turn draws new prizes");
        faces.back(
                PLAYER, faces.turning(PLAYER, WheelPlan.turning(spin.face(), 1).after()));
        assertTrue(faces.peek(PLAYER, pool).drawn(), "the refunded spin left the wheel as it was");
        assertTrue(faces.next(PLAYER, pool).drawn());
    }

    @Test
    void aSpinOnlyLooksUntilItsTurnKeepsTheWheel() {
        final WheelPlan.Face face = faces.next(PLAYER, pool).face();
        faces.ended(PLAYER);
        final WheelFaces.Next spin = faces.peek(PLAYER, pool);
        assertTrue(spin.drawn());
        assertEquals(face.parity(), spin.face().parity(), "new prizes keep the grain");
        assertTrue(faces.peek(PLAYER, pool).drawn(), "a look keeps nothing");
    }
}
