package eu.nordtal.displaytags.config;

import eu.nordtal.displaytags.api.Util;
import eu.nordtal.displaytags.api.nametag.SeeThroughMode;
import eu.nordtal.displaytags.config.spec.DisplayTagsConfigurationSpec;
import eu.nordtal.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.displaytags.wrapper.display.TextAlignment;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class NameTagConfiguration {
    private boolean enabled;
    private boolean showToSelf;
    private int updateInterval;
    private int visibilityDistance;

    private List<String> lines;
    private boolean textShadow;
    private SeeThroughMode seeThrough;
    private int sneakTextOpacity;
    private TextAlignment textAlignment;
    private String background;
    private DisplayBillboard billboard;
    private Vector offset;
    private Vector scale;

    public void load(DisplayTagsConfigurationSpec config) {
        TextAlignment alignment = parse(TextAlignment.class, "display.text-alignment", config.nametag().display().textAlignment());
        DisplayBillboard billboard = parse(DisplayBillboard.class, "display.billboard", config.nametag().display().billboard());
        SeeThroughMode seeThrough = parseSeeThrough(config.nametag().display().seeThrough());
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
        return this.lines;
    }

    public boolean hasTextShadow() {
        return this.textShadow;
    }

    /**
     * What the name tag does behind a block.
     */
    public SeeThroughMode getSeeThrough() {
        return this.seeThrough;
    }

    /**
     * The text opacity applied while a player is sneaking, or {@code -1} for
     * "fully opaque", which disables the effect.
     */
    public int getSneakTextOpacity() {
        return this.sneakTextOpacity;
    }

    public boolean hasSneakTextOpacity() {
        return this.sneakTextOpacity >= 0;
    }

    public TextAlignment getTextAlignment() {
        return this.textAlignment;
    }

    public String getBackground() {
        return this.background;
    }

    public DisplayBillboard getBillboard() {
        return this.billboard;
    }

    public Vector getOffset() {
        return this.offset;
    }

    public Vector getScale() {
        return this.scale;
    }

    /**
     * Reads an enum-valued setting and reports what is actually allowed if it does not match, so a
     * typo produces a readable message instead of a bare {@code IllegalArgumentException}.
     */
    private static <E extends Enum<E>> E parse(Class<E> type, String key, String value) {
        if (value != null) {
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Falls through to the error below.
            }
        }

        String allowed = Arrays.stream(type.getEnumConstants())
                .map((constant) -> constant.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));

        throw new IllegalArgumentException(
                "nametag." + key + ": '" + value + "' is not a valid value. Available values: " + allowed + "."
        );
    }

    /**
     * Reads {@code display.see-through}.
     * <p>
     * The setting held a YAML boolean until 2.1.1, and jcore hands such a value to a string
     * property as {@code "true"} or {@code "false"} - so a configuration written for an older
     * version parses here unchanged and keeps the behaviour it asked for.
     */
    private static SeeThroughMode parseSeeThrough(String value) {
        SeeThroughMode mode = SeeThroughMode.parse(value);
        if (mode != null) return mode;

        throw new IllegalArgumentException(
                "nametag.display.see-through: '" + value + "' is not a valid value. " +
                        "Available values: vanilla (visible through blocks but dimmed, like a " +
                        "vanilla name tag), true (visible through blocks), false (hidden behind " +
                        "blocks)."
        );
    }

    /**
     * Validates the background setting at load time. Without this the failure would only surface
     * later, while a player is joining and their name tag is being built.
     */
    private static void parseBackground(String background) {
        try {
            Util.parseDisplayBackground(background);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "nametag.display.background: '" + background + "' is not a valid value. " +
                            "Available values: 'default', 'transparent', or a hex colour such as '#FFFFFF'."
            );
        }
    }

    /**
     * Text opacity is sent as a single byte, so anything outside of -1 (fully opaque)
     * and 0-255 would wrap around into a nonsensical value.
     */
    private static int clampOpacity(int opacity) {
        if (opacity < 0) return -1;
        return Math.min(opacity, 255);
    }
}
