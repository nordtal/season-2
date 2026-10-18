package eu.nordtal.season.database.access;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The Minecraft name that {@code proxy} last saw at login for a linked account, a cache with a timestamp.
 * A name is not a key: Mojang lets another account take a released name.
 *
 * @param nameUpdated when {@code name} was last written, null together with it
 */
public record MinecraftProfile(
        @Nullable String name, @Nullable Instant nameUpdated) {

    /** Both fields empty, for an unlinked account or one that never joined. */
    public static final MinecraftProfile EMPTY = new MinecraftProfile(null, null);
}
