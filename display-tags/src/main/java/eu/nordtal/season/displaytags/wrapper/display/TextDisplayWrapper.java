package eu.nordtal.season.displaytags.wrapper.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import eu.nordtal.season.displaytags.Constants;
import java.util.List;
import net.kyori.adventure.text.Component;

public class TextDisplayWrapper extends DisplayWrapper {
    /**
     * Entity metadata indices of {@code net.minecraft.world.entity.Display$TextDisplay}.
     *
     * PacketEvents names none of them, so re-check them against the server's accessors when Minecraft changes.
     */
    private static final int INDEX_TEXT = 23;

    private static final int INDEX_LINE_WIDTH = 24;
    private static final int INDEX_BACKGROUND = 25;
    private static final int INDEX_TEXT_OPACITY = 26;
    private static final int INDEX_STYLE_FLAGS = 27;

    /**
     * Bit masks of the style flags at {@link #INDEX_STYLE_FLAGS}; both alignment bits clear means centred.
     *
     * The default-background flag (4) goes unused, since the background colour is sent explicitly.
     */
    private static final int FLAG_SHADOW = 0x01;

    private static final int FLAG_SEE_THROUGH = 0x02;
    private static final int FLAG_ALIGN_LEFT = 0x08;
    private static final int FLAG_ALIGN_RIGHT = 0x10;

    private Component text = Component.empty();
    private int lineWidth = 200;
    private int background = Constants.DEFAULT_TEXT_DISPLAY_BACKGROUND;
    private int textOpacity = -1;
    private int flags = 0;

    public TextDisplayWrapper() {
        super(EntityTypes.TEXT_DISPLAY);
    }

    @Override
    public List<EntityData<?>> getEntityData() {
        final List<EntityData<?>> data = super.getEntityData();

        data.add(new EntityData<>(INDEX_TEXT, EntityDataTypes.ADV_COMPONENT, this.text));
        data.add(new EntityData<>(INDEX_LINE_WIDTH, EntityDataTypes.INT, this.lineWidth));
        data.add(new EntityData<>(INDEX_BACKGROUND, EntityDataTypes.INT, this.background));
        data.add(new EntityData<>(INDEX_TEXT_OPACITY, EntityDataTypes.BYTE, (byte) this.textOpacity));
        data.add(new EntityData<>(INDEX_STYLE_FLAGS, EntityDataTypes.BYTE, (byte) this.flags));

        return data;
    }

    public Component getText() {
        return this.text;
    }

    public void setText(final Component text) {
        this.text = text;
    }

    public int getLineWidth() {
        return this.lineWidth;
    }

    public void setLineWidth(final int lineWidth) {
        this.lineWidth = lineWidth;
    }

    public int getBackground() {
        return this.background;
    }

    public void setBackground(final int background) {
        this.background = background;
    }

    public int getTextOpacity() {
        return this.textOpacity;
    }

    public void setTextOpacity(final int opacity) {
        this.textOpacity = opacity;
    }

    public void setTextShadow(final boolean enabled) {
        this.setFlag(FLAG_SHADOW, enabled);
    }

    public void setSeeThrough(final boolean enabled) {
        this.setFlag(FLAG_SEE_THROUGH, enabled);
    }

    public void setTextAlignment(final TextAlignment alignment) {
        this.flags &= ~(FLAG_ALIGN_LEFT | FLAG_ALIGN_RIGHT);
        switch (alignment) {
            case CENTER -> {}
            case LEFT -> this.flags |= FLAG_ALIGN_LEFT;
            case RIGHT -> this.flags |= FLAG_ALIGN_RIGHT;
        }
    }

    private void setFlag(final int mask, final boolean enabled) {
        if (enabled) {
            this.flags |= mask;
        } else {
            this.flags &= ~mask;
        }
    }
}
