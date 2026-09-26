package eu.nordtal.s2.common.access;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The Minecraft name {@code proxy} last saw at login for a linked account, a cache with a timestamp.
 *
 * The head image is not stored; it is built from {@code mc_uuid} and a configured base URL. A name is not
 * a key: Mojang lets a released name pass to another account.
 *
 * @param name        the Minecraft name last seen at login, {@code null} if never observed
 * @param nameUpdated when {@code name} was last written, {@code null} together with it
 */
public record MinecraftProfile(
        @Nullable String name, @Nullable Instant nameUpdated) {

    /** Both fields empty, the answer for an unlinked account or one nobody has seen join yet. */
    public static final MinecraftProfile EMPTY = new MinecraftProfile(null, null);
}
