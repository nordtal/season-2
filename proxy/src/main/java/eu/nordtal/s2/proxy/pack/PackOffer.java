package eu.nordtal.s2.proxy.pack;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import eu.nordtal.s2.proxy.config.PackSpec;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The resource-pack offer: one {@link ResourcePackInfo} per language, built once.
 *
 * The id derives from the SHA-1, so a client recognises the same pack across restarts and proxies.
 */
public final class PackOffer {

    private final ProxyServer proxy;
    private final PackSpec config;
    private final PackMessages messages;
    private final byte[] hash;
    private final UUID packId;

    private final Map<String, ResourcePackInfo> byLanguage = new ConcurrentHashMap<>();

    public PackOffer(final ProxyServer proxy, final PackSpec config, final PackMessages messages) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.hash = decodeHex(config.sha1());
        // Name-based (version 3) from the hash bytes: same pack, same id, every time, everywhere.
        this.packId =
                UUID.nameUUIDFromBytes(config.sha1().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The offer to send a player of {@code locale}.
     *
     * @param locale the player's language
     * @return the offer to send them
     */
    public ResourcePackInfo forLocale(final Locale locale) {
        final String language = locale == null ? "en" : locale.getLanguage().toLowerCase(Locale.ROOT);
        return byLanguage.computeIfAbsent(
                language,
                ignored -> proxy.createResourcePackBuilder(config.url())
                        .setId(packId)
                        .setHash(hash)
                        .setShouldForce(config.force())
                        .setPrompt(messages.prompt(locale))
                        .build());
    }

    /** The id every offer carries, derived from the pack's own hash. */
    public UUID packId() {
        return packId;
    }

    private static byte[] decodeHex(final String hex) {
        // Already validated by Configs#pack; fails loudly rather than risk a silently wrong hash.
        if (hex == null || hex.length() != 40) {
            throw new IllegalArgumentException("A pack SHA-1 is 40 hex characters, got: " + hex);
        }
        final byte[] bytes = new byte[20];
        for (int index = 0; index < 20; index++) {
            final int high = Character.digit(hex.charAt(index * 2), 16);
            final int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Not a hex SHA-1: " + hex);
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
