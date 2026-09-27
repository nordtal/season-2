package eu.nordtal.s2.smp.db;

import java.time.Instant;
import java.util.UUID;

/**
 * One grave as it is stored.
 *
 * A class rather than a record so the {@code serializeItemsAsBytes} contents are cloned on the way out.
 */
public final class GraveRow {

    private final UUID id;
    private final String ownerId;
    private final UUID ownerUuid;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final byte[] contents;
    private final int experience;
    private final Instant created;

    public GraveRow(
            final UUID id,
            final String ownerId,
            final UUID ownerUuid,
            final String world,
            final int x,
            final int y,
            final int z,
            final byte[] contents,
            final int experience,
            final Instant created) {
        this.id = id;
        this.ownerId = ownerId;
        this.ownerUuid = ownerUuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.contents = contents.clone();
        this.experience = experience;
        this.created = created;
    }

    public UUID id() {
        return id;
    }

    public String ownerId() {
        return ownerId;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public byte[] contents() {
        return contents.clone();
    }

    public int experience() {
        return experience;
    }

    public Instant created() {
        return created;
    }
}
