package eu.nordtal.season.database.npc;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Stores the NPCs an admin placed and the players who skip their dialogue. A change to an NPC signals its id on
 * {@link Channel#NPC}; every save of a dialogue is kept as a {@link Version}.
 */
public interface NpcStore {

    /** Returns a store that borrows {@code dataSource}. */
    static NpcStore using(final DataSource dataSource) {
        return new JdbiNpcStore(dataSource);
    }

    /** Passes every NPC to {@code apply} on connect and on every change. */
    default void follow(final SignalHub hub, final Consumer<List<Npc>> apply) {
        hub.watch(Channel.NPC, "the NPCs", null, this::npcs, apply);
    }

    /** Returns every NPC, oldest first. */
    List<Npc> npcs();

    Optional<Npc> npc(UUID id);

    /** Writes {@code npc} whole, keeps its dialogue as a version when it changed, and signals. */
    void save(Npc npc, Actor actor);

    /** Removes an NPC with its versions and skips, and signals. */
    void remove(UUID id, Actor actor);

    /** Returns every saved dialogue of an NPC, the newest first. */
    List<Version> versions(UUID id);

    /** Returns the NPCs {@code player} skips, each with the menu it goes to instead. */
    Map<UUID, String> skips(UUID player);

    /** Marks that {@code player} skips the dialogue of {@code npc} and goes to {@code menu}. */
    void skip(UUID npc, UUID player, String menu);

    /** Lets {@code player} see the dialogue of {@code npc} again. */
    void unskip(UUID npc, UUID player);

    /** One save of a dialogue. */
    record Version(long id, Dialogue dialogue, Actor actor, Instant saved) {}
}
