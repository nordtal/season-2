package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.SeeThroughMode;
import eu.nordtal.season.displaytags.Util;
import java.util.List;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/** The mutable settings of one {@link PlayerNameTag}. */
public class NameTagData {
    private boolean showToSelf = true;
    private int visibilityDistance = 32;

    private List<String> lines = List.of();
    private TextDisplay.TextAlignment textAlignment = TextDisplay.TextAlignment.CENTER;
    private Display.Billboard billboard = Display.Billboard.FIXED;

    private boolean textShadow = false;
    private SeeThroughMode seeThrough = SeeThroughMode.VANILLA;
    private int background = 1073741824;
    private int textOpacity = -1;

    private boolean sneaking = false;

    private Vector translation = new Vector(0, 0, 0);
    private Vector scale = new Vector(1, 1, 1);

    public boolean shouldShowToSelf() {
        return this.showToSelf;
    }

    public void setShowToSelf(final boolean showToSelf) {
        this.showToSelf = showToSelf;
    }

    /** Returns the maximum distance in blocks from which a viewer sees the name tag. */
    public int getVisibilityDistance() {
        return this.visibilityDistance;
    }

    public void setVisibilityDistance(final int visibilityDistance) {
        this.visibilityDistance = visibilityDistance;
    }

    /** Returns the lines drawn as MiniMessage, empty until the host writes them. */
    public List<String> getLines() {
        return this.lines;
    }

    public void setLines(final List<String> lines) {
        this.lines = lines;
    }

    public TextDisplay.TextAlignment getTextAlignment() {
        return this.textAlignment;
    }

    public void setTextAlignment(final TextDisplay.TextAlignment textAlignment) {
        this.textAlignment = textAlignment;
    }

    public Display.Billboard getBillboard() {
        return this.billboard;
    }

    public void setBillboard(final Display.Billboard billboard) {
        this.billboard = billboard;
    }

    public boolean hasTextShadow() {
        return this.textShadow;
    }

    public void setTextShadow(final boolean textShadow) {
        this.textShadow = textShadow;
    }

    public SeeThroughMode getSeeThrough() {
        return this.seeThrough;
    }

    /** Sets the mode. {@code null} means {@link SeeThroughMode#NEVER}. */
    public void setSeeThrough(final @Nullable SeeThroughMode seeThrough) {
        this.seeThrough = seeThrough == null ? SeeThroughMode.NEVER : seeThrough;
    }

    /** Returns the packed ARGB background colour, as {@link Util#parseDisplayBackground} produces it. */
    public int getBackground() {
        return this.background;
    }

    public void setBackground(final int background) {
        this.background = background;
    }

    /** Sets the background to {@code "default"}, {@code "transparent"} or a hex colour such as {@code "#FFFFFF"}. */
    public void setBackground(final String background) {
        this.background = Util.parseDisplayBackground(background);
    }

    /** Returns the text opacity, or {@code -1} for fully opaque (the vanilla default). */
    public int getTextOpacity() {
        return this.textOpacity;
    }

    public void setTextOpacity(final int textOpacity) {
        this.textOpacity = textOpacity;
    }

    /**
     * Whether the player is sneaking.
     * It is kept here, because {@code PlayerToggleSneakEvent} fires before the state changes.
     */
    public boolean isSneaking() {
        return this.sneaking;
    }

    public void setSneaking(final boolean sneaking) {
        this.sneaking = sneaking;
    }

    public Vector getTranslation() {
        return this.translation;
    }

    public void setTranslation(final Vector translation) {
        this.translation = translation;
    }

    public Vector getScale() {
        return this.scale;
    }

    public void setScale(final Vector scale) {
        this.scale = scale;
    }
}
