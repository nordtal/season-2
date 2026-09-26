package eu.nordtal.displaytags.api.nametag;

import eu.nordtal.displaytags.api.Util;
import java.util.List;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

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

    public void setShowToSelf(boolean showToSelf) {
        this.showToSelf = showToSelf;
    }

    public int getVisibilityDistance() {
        return this.visibilityDistance;
    }

    public void setVisibilityDistance(int visibilityDistance) {
        this.visibilityDistance = visibilityDistance;
    }

    public List<String> getLines() {
        return this.lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
    }

    public TextDisplay.TextAlignment getTextAlignment() {
        return this.textAlignment;
    }

    public void setTextAlignment(TextDisplay.TextAlignment textAlignment) {
        this.textAlignment = textAlignment;
    }

    public Display.Billboard getBillboard() {
        return this.billboard;
    }

    public void setBillboard(Display.Billboard billboard) {
        this.billboard = billboard;
    }

    public boolean hasTextShadow() {
        return this.textShadow;
    }

    public void setTextShadow(boolean textShadow) {
        this.textShadow = textShadow;
    }

    public SeeThroughMode getSeeThrough() {
        return this.seeThrough;
    }

    public void setSeeThrough(SeeThroughMode seeThrough) {
        this.seeThrough = seeThrough == null ? SeeThroughMode.NEVER : seeThrough;
    }

    /**
     * @return whether the name is drawn through blocks at full opacity
     * @deprecated see-through is no longer a switch with two positions - use
     *             {@link #getSeeThrough()}. This reports {@code true} only for
     *             {@link SeeThroughMode#ALWAYS}, so {@link SeeThroughMode#VANILLA} reads as
     *             {@code false} here even though such a tag <em>is</em> visible through blocks.
     */
    @Deprecated
    public boolean isSeeThrough() {
        return this.seeThrough == SeeThroughMode.ALWAYS;
    }

    /**
     * @deprecated use {@link #setSeeThrough(SeeThroughMode)}. {@code true} maps to
     *             {@link SeeThroughMode#ALWAYS} and {@code false} to {@link SeeThroughMode#NEVER},
     *             which is what these two values meant before {@link SeeThroughMode#VANILLA}
     *             existed.
     */
    @Deprecated
    public void setSeeThrough(boolean seeThrough) {
        this.seeThrough = seeThrough ? SeeThroughMode.ALWAYS : SeeThroughMode.NEVER;
    }

    public int getBackground() {
        return this.background;
    }

    public void setBackground(int background) {
        this.background = background;
    }

    public void setBackground(String background) {
        this.background = Util.parseDisplayBackground(background);
    }

    public int getTextOpacity() {
        return this.textOpacity;
    }

    public void setTextOpacity(int textOpacity) {
        this.textOpacity = textOpacity;
    }

    /**
     * Whether the player is sneaking.
     * <p>
     * This is not a cosmetic setting but the state the rendering depends on: with
     * {@link SeeThroughMode#VANILLA} a sneaking player's name is not drawn through blocks at all,
     * which is what vanilla does. It is kept here rather than read from the player because
     * {@code PlayerToggleSneakEvent} fires <em>before</em> the state is applied, so at that moment
     * {@code Player#isSneaking()} still reports the state the player is leaving.
     */
    public boolean isSneaking() {
        return this.sneaking;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    public Vector getTranslation() {
        return this.translation;
    }

    public void setTranslation(Vector translation) {
        this.translation = translation;
    }

    public Vector getScale() {
        return this.scale;
    }

    public void setScale(Vector scale) {
        this.scale = scale;
    }
}
