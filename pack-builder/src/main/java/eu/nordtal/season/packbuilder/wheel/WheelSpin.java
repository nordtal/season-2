package eu.nordtal.season.packbuilder.wheel;

import eu.nordtal.season.packbuilder.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every tick of one turn in variant {@code e}, from the press.
 * Tick t of the turn's cooldown shows the turn until the variant rests, then its rest. The static methods hold the
 * pop-in that plays when the dialog opens.
 */
final class WheelSpin {

    /** One prize's picture: drawn at ({@code x}, {@code y}) on the disc canvas. */
    record PrizeAt(int x, int y) {}

    /** The disc's picture: a frame, with or without the winner's mark. */
    record DiscAt(int frame, boolean mark) {}

    /** The overlay's picture: the pointer, each body's count where its prize shows, and the off plate. */
    record OverlayAt(WheelTurn.Pointer pointer, List<Optional<PrizeAt>> counts, boolean plate) {}

    /** A run of equal pictures, ending at tick {@code last}. */
    record Run<K>(int last, K picture) {}

    private final int e;
    private final List<WheelTurn.Tick> turn;

    WheelSpin(final int e) {
        this.e = e;
        this.turn = WheelTurn.ticks(e);
    }

    PrizeAt prize(final int body, final int t) {
        return t < turn.size() ? at(30.0 * body + turn.get(t).angle(), 0) : prizeRest(body);
    }

    PrizeAt prizeRest(final int body) {
        return at(30.0 * body + WheelTurn.distance(e), 0);
    }

    DiscAt disc(final int parity, final int t) {
        return t < turn.size()
                ? new DiscAt(WheelTurn.discFrame(30.0 * parity + turn.get(t).angle()), false)
                : discRest(parity);
    }

    DiscAt discRest(final int parity) {
        return new DiscAt(WheelTurn.discFrame(30.0 * parity + WheelTurn.distance(e)), true);
    }

    OverlayAt overlay(final int t) {
        if (t >= turn.size()) {
            return overlayRest();
        }
        final List<Optional<PrizeAt>> counts = new ArrayList<>();
        for (int b = 0; b < WheelLayout.PLACES; b++) {
            counts.add(Optional.of(prize(b, t)));
        }
        return new OverlayAt(turn.get(t).pointer(), counts, true);
    }

    OverlayAt overlayRest() {
        final List<Optional<PrizeAt>> counts = new ArrayList<>();
        for (int b = 0; b < WheelLayout.PLACES; b++) {
            counts.add(Optional.of(prizeRest(b)));
        }
        return new OverlayAt(WheelTurn.Pointer.REST, counts, false);
    }

    /** Body {@code body}'s prize at tick {@code t} of the pop-in: none before its pop, then lifted, then placed. */
    static Optional<PrizeAt> popIn(final int body, final int t) {
        final int pop = WheelTurn.POP_STEP * body;
        if (t < pop) {
            return Optional.empty();
        }
        if (t - pop < WheelTurn.POP_HOP.size()) {
            return Optional.of(at(30.0 * body, WheelTurn.POP_HOP.get(t - pop)));
        }
        return Optional.of(waiting(body));
    }

    /** Body {@code body}'s prize while the wheel waits for the press: at its own place. */
    static PrizeAt waiting(final int body) {
        return at(30.0 * body, 0);
    }

    static OverlayAt popInOverlay(final int t) {
        final List<Optional<PrizeAt>> counts = new ArrayList<>();
        for (int b = 0; b < WheelLayout.PLACES; b++) {
            counts.add(popIn(b, t));
        }
        return new OverlayAt(WheelTurn.Pointer.REST, counts, false);
    }

    static OverlayAt waitingOverlay() {
        final List<Optional<PrizeAt>> counts = new ArrayList<>();
        for (int b = 0; b < WheelLayout.PLACES; b++) {
            counts.add(Optional.of(waiting(b)));
        }
        return new OverlayAt(WheelTurn.Pointer.REST, counts, false);
    }

    static DiscAt waitingDisc(final int parity) {
        return new DiscAt(WheelTurn.discFrame(30.0 * parity), false);
    }

    private static PrizeAt at(final double angle, final int hop) {
        final int[] p = WheelLayout.upright(angle);
        return new PrizeAt(p[0], p[1] - hop);
    }

    /** The runs of equal pictures, the first picture at tick {@code from}. */
    static <K> List<Run<K>> runs(final List<K> pictures, final int from) {
        final List<Run<K>> out = new ArrayList<>();
        for (int i = 0; i < pictures.size(); i++) {
            final K k = pictures.get(i);
            if (!out.isEmpty() && out.getLast().picture().equals(k)) {
                out.set(out.size() - 1, new Run<>(from + i, k));
            } else {
                out.add(new Run<>(from + i, k));
            }
        }
        return out;
    }

    /** {@link #runs(List, int)} without the trailing ones that equal {@code rest}. */
    static <K> List<Run<K>> runs(final List<K> pictures, final K rest, final int from) {
        final List<Run<K>> out = runs(pictures, from);
        while (!out.isEmpty() && out.getLast().picture().equals(rest)) {
            out.removeLast();
        }
        return out;
    }

    /** The fraction of a {@code cooldown} from which a run that ends at tick {@code last} shows. */
    static Number threshold(final int last, final int cooldown) {
        return Json.rounded((cooldown - last - 0.5) / cooldown);
    }

    /** The turn variants of {@code count}: their end places spread over the 12 as evenly as whole places allow. */
    static List<Integer> offsets(final int count) {
        final List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add((int) Math.floor(i * (double) WheelLayout.PLACES / count + 0.5));
        }
        return out;
    }
}
