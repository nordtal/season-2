package eu.nordtal.season.database.mode;

import eu.nordtal.season.common.id.PlayerId;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Stores the state of the mode a player left on a Paper server, Survival or Creative, so the two never share an item.
 * The state the player is in lives in the server's own player data.
 */
public interface ModeStateStore {

    /** Returns a store that uses {@code dataSource} and never closes it. */
    static ModeStateStore using(final DataSource dataSource) {
        return new JdbiModeStateStore(dataSource);
    }

    /**
     * Stores {@code leaving} as the state of {@code left} and returns the stored state of {@code entered}.
     * It is empty if the player never left {@code entered} on this server. A failure stores nothing.
     */
    Optional<State> swap(String server, PlayerId player, Mode left, State leaving, Mode entered);

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

    /** @param experience the progress towards the next level, from 0 to 1 */
    record State(int level, float experience, List<Stack> stacks) {

        public State {
            stacks = List.copyOf(stacks);
        }
    }

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
