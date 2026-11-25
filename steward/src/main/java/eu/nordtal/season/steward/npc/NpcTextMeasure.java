package eu.nordtal.season.steward.npc;

import eu.nordtal.season.messagerendering.text.DialogueRoom;
import eu.nordtal.season.messagerendering.text.Letters;
import java.util.List;

/**
 * Measures an NPC text exactly as the dialogue panel draws it.
 * A body wraps to {@link DialogueRoom#BODY} and fits within its page's line limit; a name and an answer stay on one
 * line, set in bold, and fit when that line keeps to its room.
 */
public final class NpcTextMeasure {

    /** The most texts one measurement takes. */
    static final int MOST = 64;

    /** The longest text one measurement takes, in characters. */
    static final int LONGEST = 2_000;

    private NpcTextMeasure() {}

    /** The place of a measured text on the panel. */
    public enum Kind {
        NAME,
        BODY,
        ANSWER
    }

    /** A text to measure; for a body, {@code answers} says whether the page holds six lines or four. */
    public record Request(String text, Kind kind, int answers) {}

    /**
     * What the panel draws of a text, and whether it fits.
     * These are its lines, the widest line, the room of a line, the page's line limit and whether it fits both width
     * and line count.
     */
    public record Measurement(List<String> lines, int width, int room, int lineLimit, boolean fits) {
        public Measurement {
            lines = List.copyOf(lines);
        }
    }

    public static Measurement measure(final Request request) {
        if (request == null || request.text() == null || request.kind() == null) {
            throw new IllegalArgumentException("a text measurement names its text and kind");
        }
        if (request.text().length() > LONGEST) {
            throw new IllegalArgumentException("a measured text has at most " + LONGEST + " characters");
        }
        if (request.answers() < 0 || request.answers() > 3) {
            throw new IllegalArgumentException("a panel holds at most 3 answers");
        }
        final int room = switch (request.kind()) {
            case NAME -> DialogueRoom.NAME;
            case BODY -> DialogueRoom.BODY;
            case ANSWER -> DialogueRoom.LABEL;
        };
        final List<String> lines =
                request.kind() == Kind.BODY ? DialogueRoom.wrap(request.text()) : List.of(request.text());
        final int width = lines.stream()
                .mapToInt(line ->
                        request.kind() == Kind.BODY ? Letters.visibleWidth(line) : Letters.boldVisibleWidth(line))
                .max()
                .orElse(0);
        final int limit = request.kind() != Kind.BODY
                ? 1
                : request.answers() == 0 ? DialogueRoom.SPEAK_LINES : DialogueRoom.QUESTION_LINES;
        final boolean fits = width <= room && lines.size() <= limit;
        return new Measurement(lines, width, room, limit, fits);
    }
}
