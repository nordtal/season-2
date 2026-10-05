package eu.nordtal.season.displaytags.wrapper.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import eu.nordtal.season.displaytags.wrapper.EntityWrapper;
import java.util.List;
import org.bukkit.util.Vector;

public class DisplayWrapper extends EntityWrapper {
    /**
     * Entity metadata indices of {@code net.minecraft.world.entity.Display}.
     *
     * PacketEvents names none of them, so re-check them against the server's accessors when Minecraft changes.
     */
    private static final int INDEX_TRANSLATION = 11;

    private static final int INDEX_SCALE = 12;
    private static final int INDEX_BILLBOARD_CONSTRAINTS = 15;

    private Vector translation = new Vector(0, 0, 0);
    private Vector scale = new Vector(1, 1, 1);
    private DisplayBillboard billboard = DisplayBillboard.FIXED;

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
}
