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

    /** The most lines a speaking page holds without answers. */
    public static final int SPEAK_LINES = 6;

    /** The most lines a question's last page holds above its answers. */
    public static final int QUESTION_LINES = 4;

    private DialogueRoom() {}

    /** Wraps one page at spaces into lines inking at most {@link #BODY} columns, without outer spaces. */
    public static List<String> wrap(final String text) {
        return Letters.wrapVisible(text.strip(), BODY);
    }

    /**
     * Wraps {@code text} into at most {@code limit} lines of {@link #BODY} columns.
     * Overflow beyond {@code limit} lines is cut on the last kept line with an ellipsis instead of adding pages.
     */
    public static List<String> fit(final String text, final int limit) {
        if (limit < 1 || limit > SPEAK_LINES) {
            throw new IllegalArgumentException("a page holds 1 to " + SPEAK_LINES + " lines, not " + limit);
        }
        final List<String> rows = wrap(text);
        if (rows.size() <= limit) {
            return rows;
        }
        final List<String> kept = new ArrayList<>(rows.subList(0, limit - 1));
        final String tail = String.join(" ", rows.subList(limit - 1, rows.size()));
        kept.add(Letters.cut(tail, BODY));
        return List.copyOf(kept);
    }
}
