package eu.nordtal.displaytags.wrapper.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import eu.nordtal.displaytags.wrapper.EntityWrapper;
import java.util.List;
import org.bukkit.util.Vector;

public class DisplayWrapper extends EntityWrapper {
    /**
     * Entity metadata indices of {@code net.minecraft.world.entity.Display}.
     *
     * PacketEvents has no named constants for these, so a wrong number here would silently produce an
     * invisible or garbled display rather than a compile error. Verified against two independent
     * sources: the server's own {@code Display} entity data accessors read out of {@code paper.jar}
     * ({@code DATA_TRANSLATION_ID = 11}, {@code DATA_SCALE_ID = 12},
     * {@code DATA_BILLBOARD_RENDER_CONSTRAINTS_ID = 15}), and minecraft.wiki's "Java Edition
     * protocol/Entity metadata", Display section. Re-check both whenever the targeted Minecraft
     * version changes.
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
