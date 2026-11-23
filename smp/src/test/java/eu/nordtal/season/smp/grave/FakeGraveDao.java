package eu.nordtal.season.smp.grave;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Keeps the rows of {@code smp_grave} in memory, with the guards of {@link GraveDao}'s statements. */
final class FakeGraveDao implements GraveDao {

    private final Map<UUID, GraveRow> open = new LinkedHashMap<>();

    /** Runs before each contents update, outside the lock, so a test can hold a write in flight. */
    private volatile Runnable beforeUpdate = () -> {};

    void beforeUpdate(final Runnable hook) {
        beforeUpdate = hook;
    }

    UUID add(final byte[] contents) {
        final UUID id = UUID.randomUUID();
        open.put(id, row(id, contents, Instant.EPOCH));
        return id;
    }

    byte[] contents(final UUID graveId) {
        return open.get(graveId).contents();
    }

    @Override
    public synchronized GraveRow createGrave(
            final String ownerId,
            final UUID ownerUuid,
            final String world,
            final int x,
            final int y,
            final int z,
            final byte[] contents,
            final int experience) {
        final GraveRow row = row(UUID.randomUUID(), contents, Instant.EPOCH);
        open.put(row.id(), row);
        return row;
    }

    @Override
    public synchronized List<GraveRow> openGraves() {
        return new ArrayList<>(open.values());
    }

    @Override
    public synchronized Optional<UUID> markGraveLooted(final UUID id, final @Nullable String lootedBy) {
        return open.remove(id) == null ? Optional.empty() : Optional.of(id);
    }

    @Override
    public int updateGraveContents(final UUID id, final byte[] contents) {
        beforeUpdate.run();
        return update(id, contents);
    }

    private synchronized int update(final UUID id, final byte[] contents) {
        final GraveRow row = open.get(id);
        if (row == null) {
            return 0;
        }
        open.put(id, row.withContents(contents));
        return 1;
    }

    @Override
    public synchronized List<ExpiredGrave> expireGravesCreatedBefore(final Instant cutoff) {
        throw new UnsupportedOperationException("no test expires graves here");
    }

    private static GraveRow row(final UUID id, final byte[] contents, final Instant created) {
        return new GraveRow(id, "owner", UUID.randomUUID(), "world", 1, 2, 3, contents, 7, created);
    }
}
