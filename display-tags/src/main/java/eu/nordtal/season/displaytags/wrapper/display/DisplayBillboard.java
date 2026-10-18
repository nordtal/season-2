package eu.nordtal.season.displaytags.wrapper.display;

/**
 * The billboard constraints of a display as sent in entity metadata.
 * The values match {@code Display$BillboardConstraints}.
 */
public enum DisplayBillboard {
    FIXED(0),
    VERTICAL(1),
    HORIZONTAL(2),
    CENTER(3);

    public final int value;

    DisplayBillboard(final int value) {
        this.value = value;
    }
}
