package eu.nordtal.displaytags.wrapper.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import eu.nordtal.displaytags.util.ConversionUtil;
import eu.nordtal.displaytags.wrapper.EntityWrapper;
import org.bukkit.util.Vector;

import java.util.List;

public class DisplayWrapper extends EntityWrapper {
    /**
     * Entity metadata indices of {@code net.minecraft.world.entity.Display}.
     * <p>
     * PacketEvents has no named constants for these, so a wrong number here would silently produce
     * an invisible or garbled display rather than a compile error. Verified for <b>Minecraft
     * 26.2</b> on 2026-08-29 against two independent sources:
     * <ul>
     *   <li>the server's own {@code Display} entity data accessors, read out of
     *       {@code paper-26.2.jar}: {@code DATA_TRANSLATION_ID = 11},
     *       {@code DATA_SCALE_ID = 12}, {@code DATA_BILLBOARD_RENDER_CONSTRAINTS_ID = 15};</li>
     *   <li>minecraft.wiki, "Java Edition protocol/Entity metadata", Display section.</li>
     * </ul>
     * Re-check both whenever the targeted Minecraft version changes.
     */
    private static final int INDEX_TRANSLATION = 11;
    private static final int INDEX_SCALE = 12;
    private static final int INDEX_BILLBOARD_CONSTRAINTS = 15;

    private Vector translation = new Vector(0, 0, 0);
    private Vector scale = new Vector(1, 1, 1);
    private DisplayBillboard billboard = DisplayBillboard.FIXED;

    public DisplayWrapper(EntityType type) {
        super(type);
    }

    @Override
    public List<EntityData<?>> getEntityData() {
        List<EntityData<?>> data = super.getEntityData();

        data.add(new EntityData<>(
                INDEX_TRANSLATION,
                EntityDataTypes.VECTOR3F,
                ConversionUtil.fromBukkitVector(this.translation)
        ));
        data.add(new EntityData<>(
                INDEX_SCALE,
                EntityDataTypes.VECTOR3F,
                ConversionUtil.fromBukkitVector(this.scale)
        ));
        data.add(new EntityData<>(
                INDEX_BILLBOARD_CONSTRAINTS,
                EntityDataTypes.BYTE,
                (byte) this.billboard.value
        ));

        return data;
    }

    public Vector getTranslation() {
        return translation;
    }

    public void setTranslation(Vector translation) {
        this.translation = translation;
    }

    public Vector getScale() {
        return scale;
    }

    public void setScale(Vector scale) {
        this.scale = scale;
    }

    public DisplayBillboard getBillboard() {
        return billboard;
    }

    public void setBillboard(DisplayBillboard billboard) {
        this.billboard = billboard;
    }
}
