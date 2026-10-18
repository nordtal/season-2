package eu.nordtal.season.displaytags.config;

import eu.nordtal.season.displaytags.SeeThroughMode;
import eu.nordtal.season.displaytags.Util;
import eu.nordtal.season.displaytags.config.spec.NameTagConfigurationSpec;
import eu.nordtal.season.displaytags.config.spec.VectorSpec;
import eu.nordtal.season.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.season.displaytags.wrapper.display.TextAlignment;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

public class NameTagConfiguration {
    private boolean showToSelf;
    private int updateInterval;
    private int visibilityDistance;

    private boolean textShadow;
    private @Nullable SeeThroughMode seeThrough;
    private int sneakTextOpacity;
    private @Nullable TextAlignment textAlignment;
    private @Nullable String background;
    private @Nullable DisplayBillboard billboard;
    private @Nullable Vector offset;
    private @Nullable Vector scale;

    public void load(final NameTagConfigurationSpec config) {
        final TextAlignment alignment = parse(TextAlignment.class, "text-alignment", config.textAlignment());
        final DisplayBillboard billboard = parse(DisplayBillboard.class, "billboard", config.billboard());
        final SeeThroughMode seeThrough = parseSeeThrough(config.seeThrough());
        parseBackground(config.background());

        this.showToSelf = config.showToSelf();
        this.updateInterval = config.updateInterval();
        this.visibilityDistance = config.visibilityDistance();
        this.textShadow = config.textShadow();
        this.seeThrough = seeThrough;
        this.sneakTextOpacity = clampOpacity(config.sneakTextOpacity());
        this.textAlignment = alignment;
        this.background = config.background();
        this.billboard = billboard;
        this.offset = vector(config.offset());
        this.scale = vector(config.scale());
    }

    public boolean showToSelf() {
        return this.showToSelf;
    }

    public int getUpdateInterval() {
        return this.updateInterval;
    }

    public int getVisibilityDistance() {
        return this.visibilityDistance;
    }

    public boolean hasTextShadow() {
        return this.textShadow;
    }

    public SeeThroughMode getSeeThrough() {
        return Objects.requireNonNull(this.seeThrough, "load() has not run");
    }

    /** The text opacity while a player is sneaking. {@code -1} means fully opaque and disables the effect. */
    public int getSneakTextOpacity() {
        return this.sneakTextOpacity;
    }

    public boolean hasSneakTextOpacity() {
        return this.sneakTextOpacity >= 0;
    }

    public TextAlignment getTextAlignment() {
        return Objects.requireNonNull(this.textAlignment, "load() has not run");
    }

    public String getBackground() {
        return Objects.requireNonNull(this.background, "load() has not run");
    }

    public DisplayBillboard getBillboard() {
        return Objects.requireNonNull(this.billboard, "load() has not run");
    }

    public Vector getOffset() {
        return Objects.requireNonNull(this.offset, "load() has not run");
    }

    public Vector getScale() {
        return Objects.requireNonNull(this.scale, "load() has not run");
    }

    private static <E extends Enum<E>> E parse(final Class<E> type, final String key, final @Nullable String value) {
        if (value != null) {
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Falls through to the error below.
            }
        }

        final String allowed = Arrays.stream(type.getEnumConstants())
                .map((constant) -> constant.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));

        throw new IllegalArgumentException(
                key + ": '" + value + "' is not a valid value. Available values: " + allowed + ".");
    }

    /** Reads {@code see-through}. The booleans {@code "true"} and {@code "false"} are accepted. */
    private static SeeThroughMode parseSeeThrough(final @Nullable String value) {
        final SeeThroughMode mode = SeeThroughMode.parse(value);
        if (mode != null) {
            return mode;
        }

        throw new IllegalArgumentException("see-through: '" + value + "' is not a valid value. "
                + "Available values: vanilla (visible through blocks but dimmed, like a "
                + "vanilla name tag), true (visible through blocks), false (hidden behind "
                + "blocks).");
    }

    /** Refuses a bad background at load time. */
    private static void parseBackground(final String background) {
        try {
            Util.parseDisplayBackground(background);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("background: '" + background + "' is not a valid value. "
                    + "Available values: 'default', 'transparent', or a hex colour such as '#FFFFFF'.");
        }
    }

    private static Vector vector(final VectorSpec spec) {
        return new Vector(spec.x(), spec.y(), spec.z());
    }

    /** Limits the text opacity to -1 (fully opaque) or 0 to 255, because the opacity is sent as one byte. */
    private static int clampOpacity(final int opacity) {
        if (opacity < 0) {
            return -1;
        }
        return Math.min(opacity, 255);
    }
}
