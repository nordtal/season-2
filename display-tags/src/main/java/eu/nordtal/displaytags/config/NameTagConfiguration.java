package eu.nordtal.displaytags.config;

import eu.nordtal.displaytags.api.Util;
import eu.nordtal.displaytags.api.nametag.SeeThroughMode;
import eu.nordtal.displaytags.config.spec.DisplayTagsConfigurationSpec;
import eu.nordtal.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.displaytags.wrapper.display.TextAlignment;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

public class NameTagConfiguration {
    private boolean enabled;
    private boolean showToSelf;
    private int updateInterval;
    private int visibilityDistance;

    private @Nullable List<String> lines;
    private boolean textShadow;
    private @Nullable SeeThroughMode seeThrough;
    private int sneakTextOpacity;
    private @Nullable TextAlignment textAlignment;
    private @Nullable String background;
    private @Nullable DisplayBillboard billboard;
    private @Nullable Vector offset;
    private @Nullable Vector scale;

    public void load(final DisplayTagsConfigurationSpec config) {
        final TextAlignment alignment = parse(
                TextAlignment.class,
                "display.text-alignment",
                config.nametag().display().textAlignment());
        final DisplayBillboard billboard = parse(
                DisplayBillboard.class,
                "display.billboard",
                config.nametag().display().billboard());
        final SeeThroughMode seeThrough =
                parseSeeThrough(config.nametag().display().seeThrough());
        parseBackground(config.nametag().display().background());

        this.enabled = config.nametag().enabled();
        this.showToSelf = config.nametag().showToSelf();
        this.updateInterval = config.nametag().updateInterval();
        this.visibilityDistance = config.nametag().visibilityDistance();
        this.lines = config.nametag().display().lines();
        this.textShadow = config.nametag().display().textShadow();
        this.seeThrough = seeThrough;
        this.sneakTextOpacity = clampOpacity(config.nametag().display().sneakTextOpacity());
        this.textAlignment = alignment;
        this.background = config.nametag().display().background();
        this.billboard = billboard;
        this.offset = config.nametag().display().offset().toBukkitVector();
        this.scale = config.nametag().display().scale().toBukkitVector();
    }

    public boolean isEnabled() {
        return this.enabled;
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

    public List<String> getLines() {
        return Objects.requireNonNull(this.lines, "load() has not run");
    }

    public boolean hasTextShadow() {
        return this.textShadow;
    }

    /**
     * What the name tag does behind a block.
     */
    public SeeThroughMode getSeeThrough() {
        return Objects.requireNonNull(this.seeThrough, "load() has not run");
    }

    /**
     * The text opacity applied while a player is sneaking.
     *
     * {@code -1} means "fully opaque", which disables the effect.
     */
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

    /**
     * Reads an enum-valued setting.
     *
     * Reports what is actually allowed if it does not match, so a typo produces a readable message
     * instead of a bare {@code IllegalArgumentException}.
     */
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
                "nametag." + key + ": '" + value + "' is not a valid value. Available values: " + allowed + ".");
    }

    /**
     * Reads {@code display.see-through}.
     *
     * Accepts the plain YAML booleans jcore passes it as {@code "true"} or {@code "false"}.
     */
    private static SeeThroughMode parseSeeThrough(final @Nullable String value) {
        final SeeThroughMode mode = SeeThroughMode.parse(value);
        if (mode != null) {
            return mode;
        }

        throw new IllegalArgumentException("nametag.display.see-through: '" + value + "' is not a valid value. "
                + "Available values: vanilla (visible through blocks but dimmed, like a "
                + "vanilla name tag), true (visible through blocks), false (hidden behind "
                + "blocks).");
    }

    /**
     * Validates the background setting at load time.
     *
     * A bad value is refused here instead of only surfacing later while a player's name tag is built.
     */
    private static void parseBackground(final String background) {
        try {
            Util.parseDisplayBackground(background);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("nametag.display.background: '" + background + "' is not a valid value. "
                    + "Available values: 'default', 'transparent', or a hex colour such as '#FFFFFF'.");
        }
    }

    /**
     * Text opacity is sent as a single byte.
     *
     * Anything outside of -1 (fully opaque) and 0-255 would wrap around into a nonsensical value.
     */
    private static int clampOpacity(final int opacity) {
        if (opacity < 0) {
            return -1;
        }
        return Math.min(opacity, 255);
    }
}
