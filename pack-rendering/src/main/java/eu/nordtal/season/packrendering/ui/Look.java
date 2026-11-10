package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import net.kyori.adventure.text.format.TextColor;

/** The three looks of a plate, each with its glyphs and the colours of what stands on it. */
// Glyphs holds List.of lists and TextColor is a value, so nothing here changes.
@SuppressWarnings("ImmutableEnumChecker")
public enum Look {
    NORMAL(Glyphs.UI_PLATE_NORMAL, Colours.INK, Colours.SOFT),
    PRIMARY(Glyphs.UI_PLATE_PRIMARY, Colours.ON_ACCENT, Colours.ON_ACCENT),
    DISABLED(Glyphs.UI_PLATE_DISABLED, Colours.FAINT, Colours.FAINT);

    private final List<String> glyphs;
    private final TextColor ink;
    private final TextColor soft;

    Look(final List<String> glyphs, final TextColor ink, final TextColor soft) {
        this.glyphs = glyphs;
        this.ink = ink;
        this.soft = soft;
    }

    /** The left column, the right column, then body runs of 1, 2, 4 to 256 columns. */
    List<String> glyphs() {
        return glyphs;
    }

    /** The colour of a label on this plate. */
    public TextColor ink() {
        return ink;
    }

    /** The colour of a value on this plate. */
    public TextColor soft() {
        return soft;
    }
}
