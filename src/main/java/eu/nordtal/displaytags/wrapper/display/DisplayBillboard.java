package eu.nordtal.displaytags.wrapper.display;

/**
 * The values of the display's billboard render constraints, as sent in entity metadata.
 * <p>
 * Verified for Minecraft 26.2 on 2026-08-29 against {@code Display$BillboardConstraints} in
 * {@code paper-26.2.jar} (FIXED = 0, VERTICAL = 1, HORIZONTAL = 2, CENTER = 3).
 */
public enum DisplayBillboard {
    FIXED(0),
    VERTICAL(1),
    HORIZONTAL(2),
    CENTER(3);

    public final int value;

    DisplayBillboard(int value) {
        this.value = value;
    }
}
