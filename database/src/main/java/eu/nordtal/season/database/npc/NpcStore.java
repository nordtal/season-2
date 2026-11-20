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
 * A change to an NPC signals its id on {@link Channel#NPC}. Every publication of the whole NPC is kept as a
 * {@link Version}, and so is a removal, which names who removed it.
 */
public interface NpcStore {

    /** Returns a store that borrows {@code dataSource}. */
    static NpcStore using(final DataSource dataSource) {
        return new JdbiNpcStore(dataSource);
    }

    /**
     * Passes every NPC as the active pack draws it to {@code apply}, on connect, on every change and on every new pack.
     */
    default void follow(final SignalHub hub, final Consumer<List<Npc>> apply) {
        hub.watch(Channel.NPC, "the NPCs", null, this::shown, apply);
        hub.watch(Channel.PACK, "the NPCs' skins and artworks in the pack", null, this::shown, apply);
    }

    /** Returns every NPC as the active pack draws it: see {@link #shown(Npc, Packed)}. */
    default List<Npc> shown() {
        final Packed packed = packed();
        return npcs().stream().map(npc -> shown(npc, packed)).toList();
    }

    /**
     * Returns {@code npc} as a pack holding {@code packed} draws it.
     * A skin the pack does not hold yet would draw as a missing texture, so the NPC wears the default until then.
     * For an artwork it does not hold yet, the NPC shows the artwork the pack holds, or none.
     */
    static Npc shown(final Npc npc, final Packed packed) {
        final UUID skin = npc.skin();
        final UUID artwork = npc.artwork();
        final Npc dressed = skin == null || skin.equals(packed.skins().get(npc.id())) ? npc : npc.withSkin(null);
        return artwork == null || artwork.equals(packed.artworks().get(npc.id()))
                ? dressed
                : dressed.withLook(dressed.plate(), packed.artworks().get(npc.id()));
    }

    /** Returns what the active pack holds of each NPC; empty before the first pack is built. */
    Packed packed();

    /**
     * What the active pack draws of the NPCs.
     *
     * @param skins the skin id of each NPC the pack dresses, by NPC
     * @param artworks the artwork id of each NPC whose portrait the pack draws from an upload, by NPC
     */
    record Packed(Map<UUID, UUID> skins, Map<UUID, UUID> artworks) {

        /** What a pack built before any NPC had a skin or artwork holds. */
        public static final Packed NONE = new Packed(Map.of(), Map.of());

        public Packed {
            skins = Map.copyOf(skins);
            artworks = Map.copyOf(artworks);
        }
    }

    /** Returns every NPC, the one left unchanged longest first. */
    List<Npc> npcs();

    Optional<Npc> npc(UUID id);

    /** Writes {@code npc} whole, keeps every publication as a version, and signals. */
    void save(Npc npc, Actor actor);

    /**
     * Writes {@code npc} as {@link #save} does, unless another publication or removal came after the {@code read}
     * versions its editor read; then it writes nothing and returns false.
     */
    boolean publish(Npc npc, Actor actor, int read);

    /** Returns how many versions an NPC has, its publications and removals; 0 for one never published. */
    int published(UUID id);

    /** Returns how many versions each NPC has, by NPC; one never published is missing. */
    Map<UUID, Integer> published();

    /** Removes an NPC with its skips, keeps the removal as a version without a dialogue, and signals. */
    void remove(UUID id, Actor actor);

    /** Returns every publication and every removal of an NPC, the newest first. */
    List<Version> versions(UUID id);

    /** Stores an immutable uploaded skin. The game changes once an NPC that references it is saved. */
    void upload(NpcSkin skin, Actor actor);

    /** Returns an uploaded skin by its id. */
    Optional<NpcSkin> skin(UUID id);

    /** Returns the skin of every NPC that has one, by NPC id. */
    Map<UUID, NpcSkin> skins();

    /** Stores an immutable uploaded portrait picture. The game changes once an NPC that references it is saved. */
    void upload(NpcArtwork artwork, Actor actor);

    /** Returns an uploaded portrait picture by its id. */
    Optional<NpcArtwork> artwork(UUID id);

    /** Returns the portrait picture of every NPC that has one, by NPC id. */
    Map<UUID, NpcArtwork> artworks();

    /**
     * Returns the NPCs {@code player} skips, each with the menu it went to instead.
     * A skip whose menu the NPC's dialogue no longer hands off to is stale: the game plays the dialogue again.
     */
    Map<UUID, String> skips(UUID player);

    /** Marks that {@code player} skips the dialogue of {@code npc} and goes to {@code menu}. */
    void skip(UUID npc, UUID player, String menu);

    /** Lets {@code player} see the dialogue of {@code npc} again. */
    void unskip(UUID npc, UUID player);

    /**
     * One publication of an NPC, or its removal when {@code dialogue} is null.
     * {@code npc} is the whole NPC as it was published; versions written before it was kept hold only the dialogue.
     */
    record Version(
            long id, @Nullable Dialogue dialogue, @Nullable Npc npc, Actor actor, Instant saved) {}
}
