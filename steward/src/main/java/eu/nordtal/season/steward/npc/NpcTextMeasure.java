package eu.nordtal.season.steward.npc;

import eu.nordtal.season.messagerendering.text.DialogueRoom;
import eu.nordtal.season.messagerendering.text.Letters;
import java.util.List;

/**
 * Measures an NPC text exactly as the dialogue panel draws it.
 * A body wraps, breaks a word wider than a line and turns its pages by itself, so it always fits; a name and an answer
 * stay on one line, set in bold, and fit when that line keeps to its room.
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

    /** A text to measure; for a body, {@code answers} says whether a page holds four lines or three. */
    public record Request(String text, Kind kind, int answers) {}

    /**
     * What the panel draws of a text, and whether it fits.
     * These are its lines, the pages it shows them on, the widest line, the room of a line and the lines the last page
     * holds. A text that does not fit is cut with an ellipsis.
     */
    public record Measurement(
            List<String> lines, List<List<String>> pages, int width, int room, int lineLimit, boolean fits) {
        public Measurement {
            lines = List.copyOf(lines);
            pages = pages.stream().map(List::copyOf).toList();
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
                request.kind() == Kind.BODY ? Letters.wrapVisible(request.text(), room) : List.of(request.text());
        final int width = lines.stream()
                .mapToInt(line ->
                        request.kind() == Kind.BODY ? Letters.visibleWidth(line) : Letters.boldVisibleWidth(line))
                .max()
                .orElse(0);
        final int limit = request.kind() != Kind.BODY
                ? 1
                : request.answers() == 0 ? DialogueRoom.SPEAK_LINES : DialogueRoom.QUESTION_LINES;
        final List<List<String>> pages =
                request.kind() == Kind.BODY ? DialogueRoom.pages(request.text(), limit) : List.of(lines);
        return new Measurement(lines, pages, width, room, limit, width <= room);
    }
}
