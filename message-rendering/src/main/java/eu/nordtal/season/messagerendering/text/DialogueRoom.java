package eu.nordtal.season.messagerendering.text;

import java.util.ArrayList;
import java.util.List;

/** The room an NPC's dialogue panel gives its texts, shared by the panel and Steward's length check. */
public final class DialogueRoom {

    /** The columns a line of the text takes, so it keeps clear of the mark column. */
    public static final int BODY = 226;

    /** The columns the speaker's name takes in its tab, drawn bold. */
    public static final int NAME = 210;

    /** The columns an answer's label takes, so it keeps clear of its number. */
    public static final int LABEL = 213;

    /** The lines of text on a page while the NPC speaks; a longer text runs over further pages. */
    public static final int SPEAK_LINES = 4;

    /** The lines of a question above its answers, on its last page. */
    public static final int QUESTION_LINES = 3;

    /** The columns the last line of a page that others follow leaves free for its count, "99/99" and a gap of 5. */
    public static final int COUNT = 24;

    private DialogueRoom() {}

    /**
     * Breaks {@code text} into pages of the body's room.
     * The last page holds {@code lastHolds} lines, the others {@link #SPEAK_LINES}, and the last line of each page
     * that others follow keeps {@link #COUNT} free. A text that fits the last page is one page.
     */
    public static List<List<String>> pages(final String text, final int lastHolds) {
        if (lastHolds < 1 || lastHolds > SPEAK_LINES) {
            throw new IllegalArgumentException("a last page holds 1 to " + SPEAK_LINES + " lines, not " + lastHolds);
        }
        final List<List<String>> pages = new ArrayList<>();
        String rest = text;
        while (true) {
            final List<String> rows = Letters.wrapVisible(rest, BODY);
            if (rows.size() <= lastHolds) {
                pages.add(rows);
                return List.copyOf(pages);
            }
            final int whole = Math.min(SPEAK_LINES, rows.size() - 1) - 1;
            final List<String> page = new ArrayList<>(rows.subList(0, whole));
            rest = after(rest, page);
            final String tail = Letters.wrapVisible(rest, BODY - COUNT).getFirst();
            page.add(tail);
            rest = after(rest, List.of(tail));
            pages.add(List.copyOf(page));
        }
    }

    /** Returns what of {@code text} follows {@code rows}, which wrapping it began with. */
    private static String after(final String text, final List<String> rows) {
        int at = 0;
        for (final String row : rows) {
            while (!text.startsWith(row, at) && at < text.length() && text.charAt(at) == ' ') {
                at++;
            }
            if (!text.startsWith(row, at)) {
                throw new IllegalStateException("'" + row + "' does not follow in '" + text + "'");
            }
            at += row.length();
        }
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
        return text.substring(at);
    }
}
