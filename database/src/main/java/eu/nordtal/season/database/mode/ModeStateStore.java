package eu.nordtal.season.database.mode;

import eu.nordtal.season.common.id.PlayerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Stores the Survival and Creative states of the players on each Paper server, so the two never share an item.
 * Every write locks the state it changes, so a server's switch and Steward's removal never overwrite each other.
 */
public interface ModeStateStore {

    /** Returns a store that uses {@code dataSource} and never closes it. */
    static ModeStateStore using(final DataSource dataSource) {
        return new JdbiModeStateStore(dataSource);
    }

    /**
     * Stores {@code leaving} as {@code left} and returns the stored state of {@code entered}.
     * The server holds {@code entered} from then on. It is empty if the player never left it on this server.
     * A failure stores nothing.
     */
    Optional<State> swap(String server, PlayerId player, Mode left, State leaving, Mode entered);

    /** Stores the state of {@code held} as the player leaves the server, and the other mode's as not held. */
    void leave(String server, PlayerId player, Mode held, State state);

    /** Returns the state the player held on leaving the server, which the server holds from then on. */
    Optional<Held> arrive(String server, PlayerId player);

    /**
     * Stores a copy of the state the server holds of {@code held}.
     *
     * @return whether the server held it, else nothing is written
     */
    boolean copy(String server, PlayerId player, Mode held, State state);

    /** Returns every state of the player on every server, by server and mode. */
    List<Stored> states(PlayerId player);

    /** Removes the stack Steward showed in a slot of a stored state. A held state is the server's to change. */
    Removal remove(String server, PlayerId player, Mode mode, Container container, int slot, String item, int amount);

    /**
     * Writes an admin's changes to a container of a state the server does not hold, all of them or none.
     * They are written only if every slot still holds what the admin was shown, and never to a held or missing state.
     *
     * @return the slots that changed meanwhile, every slot for a held or missing state; empty once all are written
     */
    Set<Integer> edit(String server, PlayerId player, Mode mode, Container container, List<SlotChange> changes);

    /** Adventure shares Survival's state and Spectator changes none. */
    enum Mode {
        SURVIVAL,
        CREATIVE
    }

    enum Container {
        /** The main inventory, the armour and the offhand, in Paper's slot order. */
        INVENTORY,
        ENDER_CHEST
    }

    /** Where a state is. */
    enum Status {
        /** The player does not hold the mode, and the row is its state. */
        STORED,
        /** The player held the mode on leaving, and the row is its state until the player joins again. */
        AWAY,
        /** The server holds the state, and the row is a copy as of its time. */
        HELD
    }

    /** What became of a removal. */
    enum Removal {
        REMOVED,
        /** The server holds the state, and the removal is the server's. */
        HELD,
        /** The slot holds another stack, or the state is gone. */
        CHANGED
    }

    /** @param experience the progress towards the next level, from 0 to 1 */
    record State(int level, float experience, List<Stack> stacks) {

        public State {
            stacks = List.copyOf(stacks);
        }
    }

    /** The state of the mode a player held on leaving. */
    record Held(Mode mode, State state) {}

    /**
     * One slot of one container an admin changed.
     *
     * @param before the stack the admin was shown, null for an empty slot
     * @param after the stack the admin left, null for an empty slot
     */
    record SlotChange(
            int slot, @Nullable Stack before, @Nullable Stack after) {}

    /** One state as Steward reads it. */
    record Stored(String server, Mode mode, Status status, State state, Instant saved) {}

    /** One stack in one slot. */
    final class Stack {

        private final Container container;
        private final int slot;
        private final String item;
        private final int amount;
        private final byte[] data;

        /**
         * @param item the namespaced item id, such as {@code minecraft:oak_log}
         * @param data the whole stack as Paper's {@code ItemStack#serializeAsBytes} writes it
         */
        public Stack(
                final Container container, final int slot, final String item, final int amount, final byte[] data) {
            this.container = container;
            this.slot = slot;
            this.item = item;
            this.amount = amount;
            this.data = data.clone();
        }

        public Container container() {
            return container;
        }

        public int slot() {
            return slot;
        }

        public String item() {
            return item;
        }

        public int amount() {
            return amount;
        }

        public byte[] data() {
            return data.clone();
        }
    }
}
