package eu.nordtal.season.database.mode;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.Jdbis;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;

/** Implements {@link ModeStateStore} with JDBI. */
final class JdbiModeStateStore implements ModeStateStore {

    private static final State EMPTY = new State(0, 0f, List.of());

    private final Jdbi jdbi;

    JdbiModeStateStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public Optional<State> swap(
            final String server, final PlayerId player, final Mode left, final State leaving, final Mode entered) {
        return jdbi.inTransaction(handle -> {
            store(handle, server, player, left, leaving, Status.STORED);
            final Optional<State> back = read(handle, server, player, entered, true);
            // The empty state of a mode never left is the server's too, so Steward shows it as held.
            handle.createUpdate("""
                            INSERT INTO game_mode_state (server, player_uuid, mode, level, experience, saved, status)
                            VALUES (:server, :player, :mode, 0, 0, now(), 'HELD')
                            ON CONFLICT (server, player_uuid, mode) DO UPDATE SET status = 'HELD'""")
                    .bind("server", server)
                    .bind("player", player)
                    .bind("mode", entered.name())
                    .execute();
            return back;
        });
    }

    @Override
    public void leave(final String server, final PlayerId player, final Mode held, final State state) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE game_mode_state SET status = 'STORED'
                            WHERE server = :server AND player_uuid = :player AND mode <> :mode
                              AND status <> 'STORED'""")
                    .bind("server", server)
                    .bind("player", player)
                    .bind("mode", held.name())
                    .execute();
            store(handle, server, player, held, state, Status.AWAY);
        });
    }

    @Override
    public Optional<Held> arrive(final String server, final PlayerId player) {
        return jdbi.inTransaction(handle -> handle.createQuery("""
                        UPDATE game_mode_state SET status = 'HELD'
                        WHERE server = :server AND player_uuid = :player AND status = 'AWAY'
                        RETURNING mode""")
                .bind("server", server)
                .bind("player", player)
                .mapTo(String.class)
                .findOne()
                .map(Mode::valueOf)
                .map(mode ->
                        new Held(mode, read(handle, server, player, mode, false).orElseThrow())));
    }

    @Override
    public boolean copy(final String server, final PlayerId player, final Mode held, final State state) {
        return jdbi.inTransaction(handle -> {
            final boolean holds = handle.createQuery("""
                            SELECT status FROM game_mode_state
                            WHERE server = :server AND player_uuid = :player AND mode = :mode AND status = 'HELD'
                            FOR UPDATE""")
                    .bind("server", server)
                    .bind("player", player)
                    .bind("mode", held.name())
                    .mapTo(String.class)
                    .findOne()
                    .isPresent();
            if (holds) {
                store(handle, server, player, held, state, Status.HELD);
            }
            return holds;
        });
    }

    @Override
    public List<Stored> states(final PlayerId player) {
        return jdbi.inTransaction(handle -> handle
                .createQuery("""
                        SELECT server, mode, status, saved FROM game_mode_state
                        WHERE player_uuid = :player ORDER BY server, mode""")
                .bind("player", player)
                .map((row, context) -> new Stored(
                        row.getString("server"),
                        Mode.valueOf(row.getString("mode")),
                        Status.valueOf(row.getString("status")),
                        EMPTY,
                        row.getTimestamp("saved").toInstant()))
                .list()
                .stream()
                .map(row -> new Stored(
                        row.server(),
                        row.mode(),
                        row.status(),
                        read(handle, row.server(), player, row.mode(), false).orElseThrow(),
                        row.saved()))
                .toList());
    }

    @Override
    public Removal remove(
            final String server,
            final PlayerId player,
            final Mode mode,
            final Container container,
            final int slot,
            final String item,
            final int amount) {
        return jdbi.inTransaction(handle -> {
            final Optional<Status> status = lock(handle, server, player, mode);
            if (status.isEmpty()) {
                return Removal.CHANGED;
            }
            if (status.get() == Status.HELD) {
                return Removal.HELD;
            }
            final int removed = handle.createUpdate("""
                            DELETE FROM game_mode_state_item
                            WHERE server = :server AND player_uuid = :player AND mode = :mode
                              AND container = :container AND slot = :slot AND item = :item AND amount = :amount""")
                    .bind("server", server)
                    .bind("player", player)
                    .bind("mode", mode.name())
                    .bind("container", container.name())
                    .bind("slot", slot)
                    .bind("item", item)
                    .bind("amount", amount)
                    .execute();
            if (removed == 0) {
                return Removal.CHANGED;
            }
            touch(handle, server, player, mode);
            return Removal.REMOVED;
        });
    }

    @Override
    public Set<Integer> edit(
            final String server,
            final PlayerId player,
            final Mode mode,
            final Container container,
            final List<SlotChange> changes) {
        return jdbi.inTransaction(handle -> {
            final Set<Integer> refused = new TreeSet<>();
            final Optional<Status> status = lock(handle, server, player, mode);
            if (status.isEmpty() || status.get() == Status.HELD) {
                changes.forEach(change -> refused.add(change.slot()));
                return Collections.unmodifiableSet(refused);
            }
            for (final SlotChange change : changes) {
                final Optional<byte[]> now = handle.createQuery("""
                                SELECT data FROM game_mode_state_item
                                WHERE server = :server AND player_uuid = :player AND mode = :mode
                                  AND container = :container AND slot = :slot""")
                        .bind("server", server)
                        .bind("player", player)
                        .bind("mode", mode.name())
                        .bind("container", container.name())
                        .bind("slot", change.slot())
                        .mapTo(byte[].class)
                        .findOne();
                final Stack before = change.before();
                if (before == null ? now.isPresent() : now.isEmpty() || !Arrays.equals(now.get(), before.data())) {
                    refused.add(change.slot());
                    continue;
                }
                handle.createUpdate("""
                                DELETE FROM game_mode_state_item
                                WHERE server = :server AND player_uuid = :player AND mode = :mode
                                  AND container = :container AND slot = :slot""")
                        .bind("server", server)
                        .bind("player", player)
                        .bind("mode", mode.name())
                        .bind("container", container.name())
                        .bind("slot", change.slot())
                        .execute();
                final Stack after = change.after();
                if (after != null) {
                    handle.createUpdate("""
                                    INSERT INTO game_mode_state_item
                                        (server, player_uuid, mode, container, slot, item, amount, data)
                                    VALUES (:server, :player, :mode, :container, :slot, :item, :amount, :data)""")
                            .bind("server", server)
                            .bind("player", player)
                            .bind("mode", mode.name())
                            .bind("container", container.name())
                            .bind("slot", change.slot())
                            .bind("item", after.item())
                            .bind("amount", after.amount())
                            .bind("data", after.data())
                            .execute();
                }
            }
            if (refused.size() < changes.size()) {
                touch(handle, server, player, mode);
            }
            return Collections.unmodifiableSet(refused);
        });
    }

    /** Locks a state, so a server's switch or arrival waits for Steward's change, and the change for theirs. */
    private static Optional<Status> lock(
            final Handle handle, final String server, final PlayerId player, final Mode mode) {
        return handle.createQuery("""
                        SELECT status FROM game_mode_state
                        WHERE server = :server AND player_uuid = :player AND mode = :mode FOR UPDATE""")
                .bind("server", server)
                .bind("player", player)
                .bind("mode", mode.name())
                .mapTo(String.class)
                .findOne()
                .map(Status::valueOf);
    }

    private static void touch(final Handle handle, final String server, final PlayerId player, final Mode mode) {
        handle.createUpdate("""
                        UPDATE game_mode_state SET saved = now()
                        WHERE server = :server AND player_uuid = :player AND mode = :mode""")
                .bind("server", server)
                .bind("player", player)
                .bind("mode", mode.name())
                .execute();
    }

    private static void store(
            final Handle handle,
            final String server,
            final PlayerId player,
            final Mode mode,
            final State state,
            final Status status) {
        handle.createUpdate("""
                        INSERT INTO game_mode_state (server, player_uuid, mode, level, experience, saved, status)
                        VALUES (:server, :player, :mode, :level, :experience, now(), :status)
                        ON CONFLICT (server, player_uuid, mode) DO UPDATE
                            SET level = excluded.level, experience = excluded.experience, saved = excluded.saved,
                                status = excluded.status""")
                .bind("server", server)
                .bind("player", player)
                .bind("mode", mode.name())
                .bind("level", state.level())
                .bind("experience", state.experience())
                .bind("status", status.name())
                .execute();
        handle.createUpdate("""
                        DELETE FROM game_mode_state_item
                        WHERE server = :server AND player_uuid = :player AND mode = :mode""")
                .bind("server", server)
                .bind("player", player)
                .bind("mode", mode.name())
                .execute();
        if (state.stacks().isEmpty()) {
            return;
        }
        final PreparedBatch insert = handle.prepareBatch("""
                INSERT INTO game_mode_state_item (server, player_uuid, mode, container, slot, item, amount, data)
                VALUES (:server, :player, :mode, :container, :slot, :item, :amount, :data)""");
        for (final Stack stack : state.stacks()) {
            insert.bind("server", server)
                    .bind("player", player)
                    .bind("mode", mode.name())
                    .bind("container", stack.container().name())
                    .bind("slot", stack.slot())
                    .bind("item", stack.item())
                    .bind("amount", stack.amount())
                    .bind("data", stack.data())
                    .add();
        }
        insert.execute();
    }

    private static Optional<State> read(
            final Handle handle, final String server, final PlayerId player, final Mode mode, final boolean lock) {
        final Optional<State> stored = handle.createQuery("""
                        SELECT level, experience FROM game_mode_state
                        WHERE server = :server AND player_uuid = :player AND mode = :mode""" + (lock ? " FOR UPDATE" : ""))
                .bind("server", server)
                .bind("player", player)
                .bind("mode", mode.name())
                .map((rows, context) -> new State(rows.getInt("level"), rows.getFloat("experience"), List.of()))
                .findOne();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        final List<Stack> stacks = handle.createQuery("""
                        SELECT container, slot, item, amount, data FROM game_mode_state_item
                        WHERE server = :server AND player_uuid = :player AND mode = :mode
                        ORDER BY container, slot""")
                .bind("server", server)
                .bind("player", player)
                .bind("mode", mode.name())
                .map((rows, context) -> new Stack(
                        Container.valueOf(rows.getString("container")),
                        rows.getInt("slot"),
                        rows.getString("item"),
                        rows.getInt("amount"),
                        rows.getBytes("data")))
                .list();
        return Optional.of(new State(stored.get().level(), stored.get().experience(), stacks));
    }
}
