package eu.nordtal.displaytags.api.nametag;

import eu.nordtal.displaytags.api.Util;
import java.util.List;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The mutable settings behind a single {@link PlayerNameTag}.
 */
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

    /**
     * @return whether the name is shown to the player it belongs to
     */
    public boolean shouldShowToSelf() {
        return this.showToSelf;
    }

    /**
     * @param showToSelf whether the name is shown to the player it belongs to
     */
    public void setShowToSelf(final boolean showToSelf) {
        this.showToSelf = showToSelf;
    }

    /**
     * @return the maximum distance, in blocks, a viewer can see the name tag from
     */
    public int getVisibilityDistance() {
        return this.visibilityDistance;
    }

    /**
     * @param visibilityDistance the maximum distance, in blocks, a viewer can see the name tag from
     */
    public void setVisibilityDistance(final int visibilityDistance) {
        this.visibilityDistance = visibilityDistance;
    }

    /**
     * @return the configured lines, before any placeholder substitution
     */
    public List<String> getLines() {
        return this.lines;
    }

    /**
     * @param lines the configured lines, before any placeholder substitution
     */
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

    /**
     * @return whether the text is drawn with a shadow
     */
    public boolean hasTextShadow() {
        return this.textShadow;
    }

    /**
     * @param textShadow whether the text is drawn with a shadow
     */
    public void setTextShadow(final boolean textShadow) {
        this.textShadow = textShadow;
    }

    public SeeThroughMode getSeeThrough() {
        return this.seeThrough;
    }

    /**
     * @param seeThrough the new mode, or {@code null} to fall back to {@link SeeThroughMode#NEVER}
     */
    public void setSeeThrough(final @Nullable SeeThroughMode seeThrough) {
        this.seeThrough = seeThrough == null ? SeeThroughMode.NEVER : seeThrough;
    }

    /**
     * @return the packed ARGB background colour, as produced by {@link Util#parseDisplayBackground}
     */
    public int getBackground() {
        return this.background;
    }

    /**
     * @param background the packed ARGB background colour
     */
    public void setBackground(final int background) {
        this.background = background;
    }

    /**
     * @param background {@code "default"}, {@code "transparent"}, or a hex colour such as
     *                   {@code "#FFFFFF"}
     */
    public void setBackground(final String background) {
        this.background = Util.parseDisplayBackground(background);
    }

    /**
     * @return the text opacity, or {@code -1} for "fully opaque" (the vanilla default)
     */
    public int getTextOpacity() {
        return this.textOpacity;
    }

    /**
     * @param textOpacity the text opacity, or {@code -1} for "fully opaque"
     */
    public void setTextOpacity(final int textOpacity) {
        this.textOpacity = textOpacity;
    }

    /**
     * Whether the player is sneaking, kept here since {@code PlayerToggleSneakEvent} fires before the state changes.
     *
     * @return whether the player is sneaking
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
