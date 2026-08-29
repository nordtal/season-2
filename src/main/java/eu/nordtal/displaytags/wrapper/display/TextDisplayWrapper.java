package eu.nordtal.displaytags.wrapper.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import eu.nordtal.displaytags.util.Constants;
import net.kyori.adventure.text.Component;

import java.util.List;

public class TextDisplayWrapper extends DisplayWrapper {
    /**
     * Entity metadata indices of {@code net.minecraft.world.entity.Display$TextDisplay}.
     * <p>
     * PacketEvents has no named constants for these, so a wrong number here would silently produce
     * an invisible or garbled name tag rather than a compile error. Verified for <b>Minecraft
     * 26.2</b> on 2026-08-29 against two independent sources:
     * <ul>
     *   <li>the server's own {@code Display$TextDisplay} entity data accessors, read out of
     *       {@code paper-26.2.jar}: {@code DATA_TEXT_ID = 23}, {@code DATA_LINE_WIDTH_ID = 24},
     *       {@code DATA_BACKGROUND_COLOR_ID = 25}, {@code DATA_TEXT_OPACITY_ID = 26},
     *       {@code DATA_STYLE_FLAGS_ID = 27};</li>
     *   <li>minecraft.wiki, "Java Edition protocol/Entity metadata", Text Display section.</li>
     * </ul>
     * Re-check both whenever the targeted Minecraft version changes.
     */
    private static final int INDEX_TEXT = 23;
    private static final int INDEX_LINE_WIDTH = 24;
    private static final int INDEX_BACKGROUND = 25;
    private static final int INDEX_TEXT_OPACITY = 26;
    private static final int INDEX_STYLE_FLAGS = 27;

    /**
     * Bit masks of the style flags at {@link #INDEX_STYLE_FLAGS}, verified against the same
     * {@code Display$TextDisplay} constants: {@code FLAG_SHADOW = 1}, {@code FLAG_SEE_THROUGH = 2},
     * {@code FLAG_USE_DEFAULT_BACKGROUND = 4} (unused here - the background colour is sent
     * explicitly), {@code FLAG_ALIGN_LEFT = 8}, {@code FLAG_ALIGN_RIGHT = 16}. Both bits clear
     * means centred.
     */
    private static final int FLAG_SHADOW = 0x01;
    private static final int FLAG_SEE_THROUGH = 0x02;
    private static final int FLAG_ALIGN_LEFT = 0x08;
    private static final int FLAG_ALIGN_RIGHT = 0x10;

    private Component text = Component.empty();
    private int lineWidth = 200; // Default line width
    private int background = Constants.DEFAULT_TEXT_DISPLAY_BACKGROUND; // Default background
    private int textOpacity = -1; // Default text opacity
    private int flags = 0;

    public TextDisplayWrapper() {
        super(EntityTypes.TEXT_DISPLAY);
    }

    @Override
    public List<EntityData<?>> getEntityData() {
        List<EntityData<?>> data = super.getEntityData();

        data.add(new EntityData<>(
                INDEX_TEXT,
                EntityDataTypes.ADV_COMPONENT,
                text
        ));
        data.add(new EntityData<>(
                INDEX_LINE_WIDTH,
                EntityDataTypes.INT,
                this.lineWidth
        ));
        data.add(new EntityData<>(
                INDEX_BACKGROUND,
                EntityDataTypes.INT,
                this.background
        ));
        data.add(new EntityData<>(
                INDEX_TEXT_OPACITY,
                EntityDataTypes.BYTE,
                (byte) this.textOpacity
        ));
        data.add(new EntityData<>(
                INDEX_STYLE_FLAGS,
                EntityDataTypes.BYTE,
                (byte) this.flags
        ));

        return data;
    }

    public Component getText() {
        return this.text;
    }

    public void setText(Component text) {
        this.text = text;
    }

    public int getLineWidth() {
        return this.lineWidth;
    }

    public void setLineWidth(int lineWidth) {
        this.lineWidth = lineWidth;
    }

    public int getBackground() {
        return this.background;
    }

    public void setBackground(int background) {
        this.background = background;
    }

    public int getTextOpacity() {
        return this.textOpacity;
    }

    public void setTextOpacity(int opacity) {
        this.textOpacity = opacity;
    }

    public void setTextShadow(boolean enabled) {
        setFlag(FLAG_SHADOW, enabled);
    }

    public void setSeeThrough(boolean enabled) {
        setFlag(FLAG_SEE_THROUGH, enabled);
    }

    public void setTextAlignment(TextAlignment alignment) {
        flags &= ~(FLAG_ALIGN_LEFT | FLAG_ALIGN_RIGHT);
        switch (alignment) {
            case CENTER -> {}
            case LEFT -> flags |= FLAG_ALIGN_LEFT;
            case RIGHT -> flags |= FLAG_ALIGN_RIGHT;
        }
    }

    private void setFlag(int mask, boolean enabled) {
        if (enabled) {
            flags |= mask;
        } else {
            flags &= ~mask;
        }
    }
}
