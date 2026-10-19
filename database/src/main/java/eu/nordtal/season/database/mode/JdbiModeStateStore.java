package eu.nordtal.season.database.mode;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.Jdbis;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;

/** Implements {@link ModeStateStore} with JDBI. */
final class JdbiModeStateStore implements ModeStateStore {

    private final Jdbi jdbi;

    JdbiModeStateStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public Optional<State> swap(
            final String server, final PlayerId player, final Mode left, final State leaving, final Mode entered) {
        return jdbi.inTransaction(handle -> {
            store(handle, server, player, left, leaving);
            return read(handle, server, player, entered);
        });
    }

    private static void store(
            final Handle handle, final String server, final PlayerId player, final Mode mode, final State state) {
        handle.createUpdate("""
                        INSERT INTO game_mode_state (server, player_uuid, mode, level, experience, saved)
                        VALUES (:server, :player, :mode, :level, :experience, now())
                        ON CONFLICT (server, player_uuid, mode) DO UPDATE
                            SET level = excluded.level, experience = excluded.experience, saved = excluded.saved""")
                .bind("server", server)
                .bind("player", player)
                .bind("mode", mode.name())
                .bind("level", state.level())
                .bind("experience", state.experience())
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
            final Handle handle, final String server, final PlayerId player, final Mode mode) {
        final Optional<State> stored = handle.createQuery("""
                        SELECT level, experience FROM game_mode_state
                        WHERE server = :server AND player_uuid = :player AND mode = :mode""")
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
