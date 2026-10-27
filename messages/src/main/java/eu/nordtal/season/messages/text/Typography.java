package eu.nordtal.season.messages.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The typography every packaged text keeps: no text symbol as a separator, no alignment, no dash. */
public final class Typography {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACED_HYPHEN = Pattern.compile("(^|\\s)-(\\s|$)");
    private static final Pattern DOUBLE_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern DASH = Pattern.compile("[\\u2013\\u2014]");

    public static final String SEPARATOR =
            "a hyphen between spaces or a pipe stands for a separator: use a comma, a colon, a new sentence or a new line";
    public static final String ALIGNED =
            "two spaces in a row do not line up in every font: use one space or a new line";
    public static final String DASHED = "an en dash or an em dash is not used in a text: use a hyphen for a range";

    private Typography() {}

    /** Returns the rule each problem of {@code text} breaks, one of this class's constants. */
    public static List<String> problems(final String text) {
        final List<String> problems = new ArrayList<>();
        final String untagged = TAG.matcher(text).replaceAll("");
        if (SPACED_HYPHEN.matcher(untagged).find() || untagged.indexOf('|') >= 0) {
            problems.add(SEPARATOR);
        }
        if (DOUBLE_SPACE.matcher(text).find()) {
            problems.add(ALIGNED);
        }
        if (DASH.matcher(text).find()) {
            problems.add(DASHED);
        }
        return problems;
    }
}
