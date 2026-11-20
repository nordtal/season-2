package eu.nordtal.season.packrendering.ui;

import java.util.List;

/** The pack text measurements shared with Steward. */
public final class Letters {

    public static final int MISSING = eu.nordtal.season.messagerendering.text.Letters.MISSING;
    public static final String ELLIPSIS = eu.nordtal.season.messagerendering.text.Letters.ELLIPSIS;

    private Letters() {}

    /** Returns how far {@code codePoint} moves the cursor. */
    public static int advance(final int codePoint) {
        return eu.nordtal.season.messagerendering.text.Letters.advance(codePoint);
    }

    /** Returns whether the ui text font carries {@code codePoint}. */
    public static boolean covers(final int codePoint) {
        return eu.nordtal.season.messagerendering.text.Letters.covers(codePoint);
    }

    /** Returns how far {@code text} moves the cursor. */
    public static int width(final String text) {
        return eu.nordtal.season.messagerendering.text.Letters.width(text);
    }

    /** Returns the columns {@code text} inks. */
    public static int visibleWidth(final String text) {
        return eu.nordtal.season.messagerendering.text.Letters.visibleWidth(text);
    }

    /** Returns how far {@code text} in bold moves the cursor, one column more for every character. */
    public static int boldWidth(final String text) {
        return eu.nordtal.season.messagerendering.text.Letters.boldWidth(text);
    }

    /** Returns the columns {@code text} inks in bold. */
    public static int boldVisibleWidth(final String text) {
        return eu.nordtal.season.messagerendering.text.Letters.boldVisibleWidth(text);
    }

    /** Returns {@code text} cut to {@code room} as it inks in bold. */
    public static String boldCut(final String text, final int room) {
        return eu.nordtal.season.messagerendering.text.Letters.boldCut(text, room);
    }

    /** Returns {@code text} cut to {@code room}. */
    public static String cut(final String text, final int room) {
        return eu.nordtal.season.messagerendering.text.Letters.cut(text, room);
    }

    /** Breaks {@code text} into rows of at most {@code wrap} advance. */
    public static List<String> wrap(final String text, final int wrap) {
        return eu.nordtal.season.messagerendering.text.Letters.wrap(text, wrap);
    }

    /** Breaks {@code text} into lines inking at most {@code room} columns. */
    public static List<String> wrapVisible(final String text, final int room) {
        return eu.nordtal.season.messagerendering.text.Letters.wrapVisible(text, room);
    }
}
