package eu.nordtal.season.database.npc;

import eu.nordtal.season.database.Jdbis;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;

final class JdbiNpcMenuStore implements NpcMenuStore {

    private final Jdbi jdbi;

    JdbiNpcMenuStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void publish(final String server, final List<NpcMenu> menus) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM npc_menu WHERE server = :server")
                    .bind("server", server)
                    .execute();
            menus.forEach(menu -> handle.createUpdate("""
                            INSERT INTO npc_menu (key, hint_en, hint_de, server, changed)
                            VALUES (:key, :en, :de, :server, now())
                            ON CONFLICT (key) DO UPDATE SET hint_en = excluded.hint_en, hint_de = excluded.hint_de,
                                server = excluded.server, changed = excluded.changed""")
                    .bind("key", menu.key())
                    .bind("en", menu.hint().en())
                    .bind("de", menu.hint().de())
                    .bind("server", server)
                    .execute());
        });
    }

    @Override
    public List<NpcMenu> menus() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT key, hint_en, hint_de FROM npc_menu ORDER BY key")
                .map((rows, context) -> new NpcMenu(
                        rows.getString("key"),
                        new Dialogue.Words(rows.getString("hint_en"), rows.getString("hint_de"))))
                .list());
    }
}
