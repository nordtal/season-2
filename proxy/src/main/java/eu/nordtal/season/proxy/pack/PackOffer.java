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

/**
 * The offer of one {@link PackKind} with one {@link ResourcePackInfo} for each language.
 * The pack id derives from the SHA-1.
 */
public final class PackOffer {

    private final ProxyServer proxy;
    private final PackKind kind;
    private final PackMessages messages;
    // The values are copied because a running setting's one instance always reads the newest ones.
    private final String url;
    private final String sha1;
    private final boolean force;
    private final byte[] hash;
    private final UUID packId;

    private final Map<String, ResourcePackInfo> byLanguage = new ConcurrentHashMap<>();

    public PackOffer(final ProxyServer proxy, final PackKind kind, final PackSpec config, final PackMessages messages) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.url = kind.url(config);
        this.sha1 = kind.sha1(config);
        this.force = config.force();
        this.hash = decodeHex(sha1);
        this.packId = idOf(sha1);
    }

    /** Returns the pack id of the pack with the SHA-1 {@code sha1}, the same for every offer of that pack. */
    public static UUID idOf(final String sha1) {
        return UUID.nameUUIDFromBytes(sha1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** Returns whether this offer is the one {@code spec} describes, so a changed setting makes a new one. */
    public boolean offers(final PackSpec spec) {
        return url.equals(kind.url(spec)) && sha1.equals(kind.sha1(spec)) && force == spec.force();
    }

    /** Returns the SHA-1 of the offered pack. */
    public String sha1() {
        return sha1;
    }

    /** Returns the offer for the language. */
    public ResourcePackInfo forLocale(final Locale locale) {
        final String language =
                locale == null ? Locales.DEFAULT_TAG : locale.getLanguage().toLowerCase(Locale.ROOT);
        return byLanguage.computeIfAbsent(
                language,
                ignored -> proxy.createResourcePackBuilder(url)
                        .setId(packId)
                        .setHash(hash)
                        .setShouldForce(force)
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
