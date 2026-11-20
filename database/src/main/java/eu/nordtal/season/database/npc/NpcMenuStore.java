package eu.nordtal.season.database.npc;

import java.util.List;
import javax.sql.DataSource;

/** Stores the dialog menus each server registers for NPC dialogues. */
public interface NpcMenuStore {

    /** Returns a store that borrows {@code dataSource}. */
    static NpcMenuStore using(final DataSource dataSource) {
        return new JdbiNpcMenuStore(dataSource);
    }

    /** Replaces the menus registered by {@code server}. */
    void publish(String server, List<NpcMenu> menus);

    /** Returns every registered menu by key. */
    List<NpcMenu> menus();
}
