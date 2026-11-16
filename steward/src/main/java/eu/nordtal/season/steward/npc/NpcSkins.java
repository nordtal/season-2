package eu.nordtal.season.steward.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.http.Reply;
import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.npc.NpcSkin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jspecify.annotations.Nullable;

/** Reads uploaded skins, and copies an account's skin from Mojang without keeping a link to the account. */
public final class NpcSkins implements NpcSkinSource {

    /** The largest upload, in bytes. */
    static final int LIMIT = 1024 * 1024;

    private static final int SIZE = 64;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern UNDASHED = Pattern.compile("[0-9a-fA-F]{32}");
    private static final String PROFILES = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String NAMES = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String TEXTURES = "textures.minecraft.net";

    private final String profiles;
    private final String names;
    private final String textureHost;
    private final WebClient web;

    /** Returns a reader of Mojang's session server, name lookup and texture server. */
    public static NpcSkins mojang() {
        return new NpcSkins(PROFILES, NAMES, TEXTURES, WebClient.create(Duration.ofSeconds(10)));
    }

    NpcSkins(final String profiles, final String names, final String textureHost, final WebClient web) {
        this.profiles = profiles;
        this.names = names;
        this.textureHost = textureHost;
        this.web = web;
    }

    @Override
    public NpcSkin uploaded(final byte[] bytes, final NpcSkin.SkinModel model) throws IOException {
        if (bytes.length > LIMIT
                || bytes.length < PNG.length
                || !Arrays.equals(PNG, Arrays.copyOf(bytes, PNG.length))) {
            throw new NotASkin("not a PNG of at most 1 MiB");
        }
        checkSize(bytes);
        return new NpcSkin(UUID.randomUUID(), model, Base64.getEncoder().encodeToString(bytes));
    }

    @Override
    public NpcSkin copy(final String player) throws IOException {
        final String id = idOf(player.strip());
        final Reply profile = web.get(URI.create(profiles + id));
        if (profile.status() == 204 || profile.status() == 404 || profile.status() == 400) {
            throw new UnknownPlayer(player);
        }
        final JsonObject skin = skinOf(Json.decode(profile.okBody(), JsonObject.class));
        if (skin == null || !skin.has("url")) {
            throw new NoSkin(player);
        }
        final URI address = URI.create(skin.get("url").getAsString());
        if (!textureHost.equals(address.getHost())) {
            throw new IOException("Mojang named a skin outside its texture server");
        }
        final byte[] bytes;
        try (InputStream input = web.stream(https(address), Duration.ofSeconds(15), "image/png")) {
            bytes = input.readNBytes(LIMIT + 1);
        }
        final boolean slim = skin.get("metadata") instanceof final JsonObject metadata
                && metadata.has("model")
                && "slim".equals(metadata.get("model").getAsString());
        try {
            return uploaded(bytes, slim ? NpcSkin.SkinModel.SLIM : NpcSkin.SkinModel.CLASSIC);
        } catch (final NotASkin legacy) {
            throw new NoSkin(player);
        }
    }

    /** Reads only the PNG's header, so a small file that names a huge image is refused before it is decoded. */
    private static void checkSize(final byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new NotASkin("an unreadable PNG");
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != SIZE || reader.getHeight(0) != SIZE) {
                    throw new NotASkin("not 64 by 64 pixels");
                }
                if (reader.read(0) == null) {
                    throw new NotASkin("an unreadable PNG");
                }
            } finally {
                reader.dispose();
            }
        } catch (final IOException unreadable) {
            throw new NotASkin("an unreadable PNG");
        }
    }

    /** Returns the undashed UUID of {@code player}, a name or a UUID, asking Mojang for a name's. */
    private String idOf(final String player) throws IOException {
        if (UNDASHED.matcher(player).matches()) {
            return player.toLowerCase(java.util.Locale.ROOT);
        }
        try {
            return UUID.fromString(player).toString().replace("-", "");
        } catch (final IllegalArgumentException notAUuid) {
            // A name, looked up below.
        }
        if (!NAME.matcher(player).matches()) {
            throw new UnknownPlayer(player);
        }
        final Reply found = web.get(URI.create(names + player));
        if (found.status() == 204 || found.status() == 404) {
            throw new UnknownPlayer(player);
        }
        final JsonObject account = Json.decode(found.okBody(), JsonObject.class);
        if (account == null || !account.has("id")) {
            throw new UnknownPlayer(player);
        }
        return account.get("id").getAsString();
    }

    private static @Nullable JsonObject skinOf(final @Nullable JsonObject profile) throws IOException {
        if (profile == null || !(profile.get("properties") instanceof final JsonArray properties)) {
            throw new IOException("Mojang answered no profile properties");
        }
        for (final JsonElement property : properties) {
            final JsonObject own = property.getAsJsonObject();
            if (own.has("name") && "textures".equals(own.get("name").getAsString())) {
                final String decoded =
                        new String(Base64.getDecoder().decode(own.get("value").getAsString()), StandardCharsets.UTF_8);
                final JsonObject textures =
                        Json.decode(decoded, JsonObject.class).getAsJsonObject("textures");
                return textures == null ? null : textures.getAsJsonObject("SKIN");
            }
        }
        throw new IOException("Mojang's profile has no textures");
    }

    /** Mojang names its textures over http, and its texture server answers the same over https. */
    private static URI https(final URI address) {
        return TEXTURES.equals(address.getHost()) && "http".equals(address.getScheme())
                ? URI.create("https" + address.toString().substring("http".length()))
                : address;
    }
}
