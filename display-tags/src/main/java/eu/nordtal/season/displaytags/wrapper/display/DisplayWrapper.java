package eu.nordtal.season.displaytags.wrapper.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import eu.nordtal.season.displaytags.wrapper.EntityWrapper;
import java.util.List;
import org.bukkit.util.Vector;

public class DisplayWrapper extends EntityWrapper {
    /** Entity metadata indices of {@code net.minecraft.world.entity.Display}. PacketEvents names none of them. */
    private static final int INDEX_TRANSLATION = 11;

    private static final int INDEX_SCALE = 12;
    private static final int INDEX_BILLBOARD_CONSTRAINTS = 15;
    private static final int INDEX_BRIGHTNESS_OVERRIDE = 16;

    /** No override: the display takes the light where it stands. */
    private static final int WORLD_LIGHT = -1;

    private static final int BLOCK_LIGHT_SHIFT = 4;
    private static final int SKY_LIGHT_SHIFT = 20;

    private Vector translation = new Vector(0, 0, 0);
    private Vector scale = new Vector(1, 1, 1);
    private DisplayBillboard billboard = DisplayBillboard.FIXED;
    private int brightness = WORLD_LIGHT;

    public DisplayWrapper(final EntityType type) {
        super(type);
    }

    @Override
    public List<EntityData<?>> getEntityData() {
        final List<EntityData<?>> data = super.getEntityData();

        data.add(new EntityData<>(
                INDEX_TRANSLATION, EntityDataTypes.VECTOR3F, ConversionUtil.fromBukkitVector(this.translation)));
        data.add(new EntityData<>(INDEX_SCALE, EntityDataTypes.VECTOR3F, ConversionUtil.fromBukkitVector(this.scale)));
        data.add(new EntityData<>(INDEX_BILLBOARD_CONSTRAINTS, EntityDataTypes.BYTE, (byte) this.billboard.value));
        data.add(new EntityData<>(INDEX_BRIGHTNESS_OVERRIDE, EntityDataTypes.INT, this.brightness));

        return data;
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

    public DisplayBillboard getBillboard() {
        return this.billboard;
    }

    public void setBillboard(final DisplayBillboard billboard) {
        this.billboard = billboard;
    }

    /** Draws the display at {@code light} (0 to 15) for block and sky light, whatever the light where it stands. */
    public void setBrightness(final int light) {
        this.brightness = light << BLOCK_LIGHT_SHIFT | light << SKY_LIGHT_SHIFT;
    }
}
