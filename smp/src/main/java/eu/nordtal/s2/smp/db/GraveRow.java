package eu.nordtal.s2.smp.db;

import java.time.Instant;
import java.util.UUID;

/**
 * One grave as it is stored.
 *
 * Not a record: {@code contents} is a {@code byte[]}, and a record component may never be one - a record's canonical
 * accessor hands back the field itself, so anyone holding the row could mutate the stored bytes. A plain class with
 * a cloning accessor keeps the row itself immutable.
 *
 * {@code contents} is {@code ItemStack.serializeItemsAsBytes} - NBT with the server's own data fixers behind it,
 * which is the format that survives a Minecraft update. Bukkit's {@code ConfigurationSerializable} map was rejected
 * because it loses data components that have no map representation, and a hand-rolled format because it would have
 * to be taught every new item component by hand. Nothing ever queries <em>into</em> a grave; it is written once and
 * read back whole, so there is nothing traded away.
 *
 * {@code ownerUuid} is joined in from {@code account_link} rather than stored: the schema is keyed by Discord
 * account throughout, and the Minecraft UUID is only wanted here so the head on top of the grave is the right
 * person's face.
 *
 * {@code created} is the same column {@code expireGravesOlderThan} ages a grave by - it is what the hologram over
 * the grave counts down against, so a restart never resets the number either.
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
