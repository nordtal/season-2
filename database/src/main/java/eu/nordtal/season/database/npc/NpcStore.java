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
import org.jspecify.annotations.Nullable;

/**
 * Stores the NPCs an admin placed and the players who skip their dialogue.
 * A change to an NPC signals its id on {@link Channel#NPC}. Every save of a dialogue is kept as a {@link Version}, and
 * so is a removal, which names who removed it.
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

    /** Returns every NPC, the one left unchanged longest first. */
    List<Npc> npcs();

    Optional<Npc> npc(UUID id);

    /** Writes {@code npc} whole, keeps its dialogue as a version when it changed, and signals. */
    void save(Npc npc, Actor actor);

    /** Removes an NPC with its skips, keeps the removal as a version without a dialogue, and signals. */
    void remove(UUID id, Actor actor);

    /** Returns every save and every removal of an NPC's dialogue, the newest first. */
    List<Version> versions(UUID id);

    /**
     * Returns the NPCs {@code player} skips, each with the menu it went to instead.
     * A skip whose menu the NPC's dialogue no longer hands off to is stale: the game plays the dialogue again.
     */
    Map<UUID, String> skips(UUID player);

    /** Marks that {@code player} skips the dialogue of {@code npc} and goes to {@code menu}. */
    void skip(UUID npc, UUID player, String menu);

    /** Lets {@code player} see the dialogue of {@code npc} again. */
    void unskip(UUID npc, UUID player);

    /** One save of a dialogue, or its removal when {@code dialogue} is null. */
    record Version(long id, @Nullable Dialogue dialogue, Actor actor, Instant saved) {}
}
