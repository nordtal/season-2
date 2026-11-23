package eu.nordtal.season.smp.wall;

/**
 * How the border wall's vertical band is placed along the terrain and in open air.
 *
 * Both approaches use the frost-aurora recoloured look and keep the terrain band steady while jumping.
 */
public enum Placement {

    /** Fixed terrain-anchored band along the edge, with a short local air band at player height. */
    AIR_FOCUS,

    /** Fixed terrain-hugging band including nearby pillars, with a soft local proximity window in air. */
    PROXIMITY_WINDOW;

    /** The default placement used when no system property overrides it. */
    static final Placement DEFAULT = AIR_FOCUS;

    static Placement configured() {
        final String property = System.getProperty("nordtal.border.placement");
        if (property == null || property.isBlank()) {
            return DEFAULT;
        }
        return switch (property.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "proximity-window", "proximity_window", "b" -> PROXIMITY_WINDOW;
            default -> AIR_FOCUS;
        };
    }
}
