package eu.nordtal.s2.common.access;

import java.time.Instant;

/**
 * The Minecraft name {@code proxy} last saw at login, for a linked account - a cache with
 * a timestamp, exactly like {@link DiscordProfile}. See
 * {@code V21__discord_and_minecraft_profile_cache.sql}.
 *
 * <p>The Minecraft head image is deliberately not part of this record: it is not something anybody
 * observes, it is a pure function of {@code mc_uuid} and a configured head-service base URL
 * (Crafatar, at the time of writing - see {@code season-2/README.md}). Storing a rendering of that
 * pair would go stale the instant the base URL is reconfigured, which defeats the entire reason the
 * URL lives in configuration rather than in source: a consumer that wants the head asset builds the
 * URL itself, from {@code mc_uuid} and its own configured base.
 *
 * <p><b>A name is not a key</b> here either: {@code mc_uuid} is what {@code account_link} is joined
 * on, {@code name} is not declared {@code UNIQUE}, and it must not be - Mojang lets a released name
 * be taken by somebody else, so the very same string can legitimately belong to two different
 * accounts across time.
 *
 * @param name        the Minecraft name last seen at login, {@code null} if never observed
 * @param nameUpdated when {@code name} was last written, {@code null} together with it
 */
public record MinecraftProfile(String name, Instant nameUpdated) {

    /**
     * Both fields empty - what an unlinked account, or a linked one nobody has seen join yet, reads
     * as. {@link AccessDirectory#minecraftProfile(String)} answers this rather than throwing or
     * returning {@code null}.
     */
    public static final MinecraftProfile EMPTY = new MinecraftProfile(null, null);
}
