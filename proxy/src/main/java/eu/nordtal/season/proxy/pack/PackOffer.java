package eu.nordtal.season.proxy.pack;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.proxy.config.PackSpec;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The resource pack offer with one {@link ResourcePackInfo} for each language. The pack id derives from the SHA-1. */
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
        this.packId =
                UUID.nameUUIDFromBytes(config.sha1().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** Returns the offer for the language. */
    public ResourcePackInfo forLocale(final Locale locale) {
        final String language =
                locale == null ? Locales.DEFAULT_TAG : locale.getLanguage().toLowerCase(Locale.ROOT);
        return byLanguage.computeIfAbsent(
                language,
                ignored -> proxy.createResourcePackBuilder(config.url())
                        .setId(packId)
                        .setHash(hash)
                        .setShouldForce(config.force())
                        .setPrompt(messages.prompt(locale))
                        .build());
    }

    /** Returns the pack id. */
    public UUID packId() {
        return packId;
    }

    private static byte[] decodeHex(final String hex) {
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
