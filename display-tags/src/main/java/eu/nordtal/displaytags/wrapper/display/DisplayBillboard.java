package eu.nordtal.displaytags.wrapper.display;

/**
 * The values of the display's billboard render constraints, as sent in entity metadata.
 *
 * They match {@code Display$BillboardConstraints}: FIXED = 0, VERTICAL = 1, HORIZONTAL = 2, CENTER = 3.
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
