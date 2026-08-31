package eu.nordtal.s2.networkcontrol.pack;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;

import eu.nordtal.s2.networkcontrol.config.PackSpec;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The resource-pack offer itself: one {@link ResourcePackInfo} per language, built once and handed
 * out.
 *
 * <h2>Why per language</h2>
 * The prompt line inside the client's own pack dialog is a {@code Component}, and every string a
 * player reads comes out of {@code Messages} against their database locale (docs/i18n.md). Nothing
 * else about the offer varies, so the infos are cached by language rather than built per login.
 *
 * <h2>The id</h2>
 * Derived from the pack's SHA-1 rather than random, so that the same pack is the same pack across
 * proxy restarts and across proxies. The client uses the id to recognise a pack it already has;
 * a fresh random id on every start would ask every player to re-apply what they already applied.
 *
 * <h2>The hash</h2>
 * Comes from {@code pack.yml#sha1} and is sent alongside the URL. It is what lets a client skip the
 * download when it has this pack cached, and it is what makes the client refuse a zip that is not
 * the one we meant. It is never hardcoded and never guessed - see {@code PackSpec}.
 */
public final class PackOffer {

    private final ProxyServer proxy;
    private final PackSpec config;
    private final PackMessages messages;
    private final byte[] hash;
    private final UUID packId;

    private final Map<String, ResourcePackInfo> byLanguage = new ConcurrentHashMap<>();

    /**
     * @param proxy    the proxy, which owns the {@code ResourcePackInfo} builder
     * @param config   a {@code pack.yml} that has passed validation, so the URL is an http(s) URL
     *                 and the hash is 40 hex characters
     * @param messages the prompt line, per language
     */
    public PackOffer(final ProxyServer proxy, final PackSpec config, final PackMessages messages) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.hash = decodeHex(config.sha1());
        // Name-based (version 3) from the hash bytes: same pack, same id, every time, everywhere.
        this.packId = UUID.nameUUIDFromBytes(config.sha1().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @param locale the player's language
     * @return the offer to send them
     */
    public ResourcePackInfo forLocale(final Locale locale) {
        final String language = locale == null ? "en" : locale.getLanguage().toLowerCase(Locale.ROOT);
        return byLanguage.computeIfAbsent(language, ignored -> proxy
                .createResourcePackBuilder(config.url())
                .setId(packId)
                .setHash(hash)
                .setShouldForce(config.force())
                .setPrompt(messages.prompt(locale))
                .build());
    }

    /** @return the id every offer this proxy sends carries, derived from the pack's own hash */
    public UUID packId() {
        return packId;
    }

    private static byte[] decodeHex(final String hex) {
        // pack.yml has already been validated as 40 hex characters by Configs#pack, so this cannot
        // fail on a configured proxy. It is written to fail loudly rather than to produce a wrong
        // hash if that validation is ever weakened - a silently wrong hash is a pack every player
        // fails to download, reported as FAILED_DOWNLOAD, which reads as a network problem.
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
