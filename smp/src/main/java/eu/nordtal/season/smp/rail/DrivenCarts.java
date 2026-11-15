package eu.nordtal.season.smp.rail;

import java.util.UUID;

/** Told by {@link Rides} which carts it drives and who rides each; each call comes on the main thread. */
public interface DrivenCarts {

    DrivenCarts NONE = new DrivenCarts() {};

    /** A ride started to drive the cart with the entity id {@code cart}, ridden by {@code rider}. */
    default void started(final int cart, final UUID rider) {}

    /** The ride of the cart with the entity id {@code cart} ended. */
    default void ended(final int cart) {}
}
