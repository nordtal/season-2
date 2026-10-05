package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.value.ValueText;
import eu.nordtal.season.messages.value.Words;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** The plain target: a console, a log, a GUI line drawn in the pack's own sheet. Markup is left out. */
public final class PlainText {

    private PlainText() {}

    /** Returns the pieces as plain text, a {@code <newline>} as a line break and every other tag left out. */
    public static String of(final List<Piece> pieces, final Locale locale, final ZoneId zone, final Words words) {
        final StringBuilder out = new StringBuilder();
        for (final Piece piece : pieces) {
            switch (piece) {
                case Piece.Text text -> out.append(text.text());
                case Piece.Filled filled ->
                    out.append(ValueText.of(filled.kind(), filled.value(), filled.style(), locale, zone, words));
                case Piece.Markup markup -> {
                    if (Tags.isLineBreak(markup.tag().name())) {
                        out.append('\n');
                    }
                }
            }
        }
        return out.toString();
    }
}
