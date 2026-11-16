package eu.nordtal.season.steward.npc;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.npc.Dialogue;
import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.NpcMenu;
import eu.nordtal.season.database.npc.NpcMenuStore;
import eu.nordtal.season.database.npc.NpcSkin;
import eu.nordtal.season.database.npc.NpcStore;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.game.GameActions;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.journal.Unjournaled;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The API behind Steward's NPC editor: NPCs, versions, menus, the length check, skins and the preview.
 * An editor holds its draft itself; one publication writes the whole NPC as its next version, and the game follows it
 * through {@code nordtal_npc}.
 */
@Slf4j
public final class NpcApi {

    /** The longest name, as the table holds it. */
    static final int NAME = 32;

    /** The longest item key, as the table holds it. */
    static final int ITEM = 128;

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final NpcStore store;
    private final NpcMenuStore menus;
    private final NpcSkinSource skins;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final Previewer previewer;

    /** Sends a checked draft to the admin's own player and returns the name of the request to follow. */
    @FunctionalInterface
    public interface Previewer {
        String show(Context ctx, Npc npc, String language);
    }

    public NpcApi(
            final NpcStore store,
            final NpcMenuStore menus,
            final NpcSkinSource skins,
            final Function<Context, DiscordAuth.Account> accounts,
            final Previewer previewer) {
        this.store = store;
        this.menus = menus;
        this.skins = skins;
        this.accounts = accounts;
        this.previewer = previewer;
    }

    /** An NPC as published, and how many versions it has: the number of the live one. */
    public record NpcView(Npc npc, int version) {}

    /** A publication: the whole NPC, and the version its editor read, 0 for a new NPC. */
    public record NpcPublication(Npc npc, int version) {}

    /**
     * One version, numbered from 1 for the first.
     * {@code npc} is null for a removal, and for a version kept before whole NPCs were, which holds only the dialogue.
     */
    public record NpcVersion(
            int number, @Nullable Npc npc, @Nullable Dialogue dialogue, boolean removal, Actor actor, Instant saved) {}

    /** A skin as stored: its id for the NPC's {@code skin}, its arm shape and where the editor reads its PNG. */
    public record NpcSkinUploaded(UUID id, NpcSkin.SkinModel model, String address) {}

    /** The account whose current skin is copied, by its name or UUID. */
    public record NpcSkinCopy(String player) {}

    /** A draft to play on the admin's own player, in {@code language}, {@code en} or {@code de}. */
    public record NpcPreview(Npc npc, String language) {}

    /** {@code GET /api/npcs}: every NPC, the one changed longest ago first. */
    public void list(final Context ctx) {
        ctx.json(store.npcs().stream()
                .map(npc -> new NpcView(npc, store.published(npc.id())))
                .toList());
    }

    /** {@code GET /api/npcs/{id}}: one NPC as published. */
    public void read(final Context ctx) {
        final UUID id = id(ctx);
        ctx.json(new NpcView(found(id), store.published(id)));
    }

    /** {@code PUT /api/npcs/{id}}: publishes the whole NPC as its next version, unless it changed since it was read. */
    public void publish(final Context ctx) {
        final NpcPublication publication = body(ctx, NpcPublication.class);
        if (publication == null || publication.npc() == null) {
            throw new BadRequestResponse("a publication sends the NPC and the version it read");
        }
        final UUID id = id(ctx);
        if (!id.equals(publication.npc().id())) {
            throw new BadRequestResponse("the NPC's id differs from the one in the path");
        }
        final Npc npc = checked(publication.npc(), "The NPC was not published. ");
        if (!store.publish(npc, accounts.apply(ctx).actor(), publication.version())) {
            throw refused(
                    409,
                    "NPC_CHANGED",
                    npc.name().en()
                            + " was changed by someone else since you opened it. Reload it and make your change again.");
        }
        Journaling.line(ctx, null, null);
        log.info("npc published: npc={} version={}", id, publication.version() + 1);
        ctx.json(new NpcView(found(id), store.published(id)));
    }

    /** {@code DELETE /api/npcs/{id}}: removes the NPC from the game, keeping its versions. */
    public void remove(final Context ctx) {
        final UUID id = id(ctx);
        found(id);
        store.remove(id, accounts.apply(ctx).actor());
        Journaling.line(ctx, null, null);
        ctx.status(204);
    }

    /** {@code GET /api/npcs/{id}/versions}: every publication and removal, the newest first. */
    public void versions(final Context ctx) {
        final List<NpcStore.Version> versions = store.versions(id(ctx));
        final List<NpcVersion> numbered = new ArrayList<>();
        for (int at = 0; at < versions.size(); at++) {
            final NpcStore.Version version = versions.get(at);
            numbered.add(new NpcVersion(
                    versions.size() - at,
                    version.npc(),
                    version.dialogue(),
                    version.dialogue() == null,
                    version.actor(),
                    version.saved()));
        }
        ctx.json(numbered);
    }

    /** {@code GET /api/npc-menus}: the menus a dialogue may open, by key, with the game's hint in both languages. */
    public void menus(final Context ctx) {
        ctx.json(menus.menus());
    }

    /** {@code POST /api/npc-text/measure}: measures each text as the panel draws it. */
    public void measure(final Context ctx) {
        final NpcTextMeasure.Request[] texts = body(ctx, NpcTextMeasure.Request[].class);
        if (texts == null || texts.length > NpcTextMeasure.MOST) {
            throw new BadRequestResponse("a measurement sends a list of at most " + NpcTextMeasure.MOST + " texts");
        }
        try {
            ctx.json(Arrays.stream(texts).map(NpcTextMeasure::measure).toList());
        } catch (final IllegalArgumentException invalid) {
            throw new BadRequestResponse(String.valueOf(invalid.getMessage()));
        }
    }

    /** {@code POST /api/npc-preview}: plays the draft on the admin's own player, on the SMP, without publishing. */
    public void preview(final Context ctx) {
        final NpcPreview preview = body(ctx, NpcPreview.class);
        if (preview == null || preview.npc() == null || preview.language() == null) {
            throw new BadRequestResponse("a preview sends the NPC and a language");
        }
        final String language = preview.language().toLowerCase(Locale.ROOT);
        if (!language.equals("en") && !language.equals("de")) {
            throw new BadRequestResponse("a preview plays in en or de");
        }
        final Npc npc = checked(preview.npc(), "The preview was not started. ");
        GameActions.answer(ctx, previewer.show(ctx, npc, language));
    }

    /** {@code POST /api/npc-skins?model=CLASSIC|SLIM}: stores an uploaded 64 by 64 PNG as a skin. */
    public void upload(final Context ctx) {
        final String asked = ctx.queryParam("model");
        final NpcSkin.SkinModel model;
        try {
            model = asked == null ? NpcSkin.SkinModel.CLASSIC : NpcSkin.SkinModel.valueOf(asked);
        } catch (final IllegalArgumentException unknown) {
            throw new BadRequestResponse("a skin's model is CLASSIC or SLIM");
        }
        final NpcSkin skin;
        try {
            skin = skins.uploaded(ctx.bodyAsBytes(), model);
        } catch (final NpcSkinSource.NotASkin | IOException refused) {
            throw refused(
                    400, "SKIN_REFUSED", "The skin was not saved. Upload a PNG of 64 by 64 pixels, at most 1 MB.");
        }
        stored(ctx, skin);
    }

    /** {@code POST /api/npc-skins/copy}: stores the current skin of a Minecraft account as a skin of its own. */
    public void copy(final Context ctx) {
        final NpcSkinCopy copy = body(ctx, NpcSkinCopy.class);
        if (copy == null || copy.player() == null || copy.player().isBlank()) {
            throw new BadRequestResponse("a copy names the account by its name or UUID");
        }
        final NpcSkin skin;
        try {
            skin = skins.copy(copy.player());
        } catch (final NpcSkinSource.NoSkin none) {
            throw refused(
                    422,
                    "NO_SKIN_TO_COPY",
                    copy.player().strip() + " wears a default or an old skin, which an NPC cannot wear. Upload a skin"
                            + " instead.");
        } catch (final NpcSkinSource.UnknownPlayer unknown) {
            throw refused(
                    404,
                    "NO_MINECRAFT_ACCOUNT",
                    "No Minecraft account is called " + copy.player().strip() + ". Check the name or UUID.");
        } catch (final IOException unavailable) {
            log.warn("npc skin not copied: player={} cause={}", copy.player(), unavailable.getMessage());
            throw refused(
                    502,
                    "SKIN_UNAVAILABLE",
                    "The skin of " + copy.player().strip()
                            + " could not be loaded from Mojang. Try again in a few minutes.");
        }
        stored(ctx, skin);
    }

    /** {@code GET /api/npc-skins/{id}}: the PNG of a skin, which never changes. */
    public void skin(final Context ctx) {
        final NpcSkin skin = store.skin(id(ctx))
                .orElseThrow(() -> refused(404, "NO_SKIN", "This skin no longer exists. Upload it again."));
        ctx.header("Cache-Control", "private, max-age=31536000, immutable");
        ctx.contentType("image/png");
        ctx.result(Base64.getDecoder().decode(skin.png()));
    }

    public void register(final JavalinConfig cfg) {
        cfg.routes.get("/api/npcs", this::list, Gate.KEY_HELD);
        cfg.routes.get("/api/npcs/{id}", this::read, Gate.KEY_HELD);
        cfg.routes.put("/api/npcs/{id}", this::publish, Gate.KEY_HELD, Journaled.as(JournalAction.SAVE_NPC));
        cfg.routes.delete("/api/npcs/{id}", this::remove, Gate.KEY_HELD, Journaled.as(JournalAction.REMOVE_NPC));
        cfg.routes.get("/api/npcs/{id}/versions", this::versions, Gate.KEY_HELD);
        cfg.routes.get("/api/npc-menus", this::menus, Gate.KEY_HELD);
        cfg.routes.post("/api/npc-text/measure", this::measure, Gate.KEY_HELD, Unjournaled.SHOWN_TO_ONESELF);
        cfg.routes.post("/api/npc-preview", this::preview, Gate.KEY_HELD, Unjournaled.SHOWN_TO_ONESELF);
        cfg.routes.post("/api/npc-skins", this::upload, Gate.KEY_HELD, Unjournaled.SHOWN_ONCE_SAVED);
        cfg.routes.post("/api/npc-skins/copy", this::copy, Gate.KEY_HELD, Unjournaled.SHOWN_ONCE_SAVED);
        cfg.routes.get("/api/npc-skins/{id}", this::skin, Gate.KEY_HELD);
    }

    private void stored(final Context ctx, final NpcSkin skin) {
        store.upload(skin, accounts.apply(ctx).actor());
        ctx.status(201).json(new NpcSkinUploaded(skin.id(), skin.model(), "/api/npc-skins/" + skin.id()));
    }

    /** Returns the draft with its skin's model as stored, or refuses it with its first problem. */
    private Npc checked(final Npc draft, final String refusal) {
        final List<String> problems = new ArrayList<>();
        final String en = draft.name().en().strip();
        final String de = draft.name().de().strip();
        if (en.isEmpty() || de.isEmpty()) {
            problems.add("Enter the NPC's name in English and in German.");
        } else if (en.length() > NAME || de.length() > NAME) {
            problems.add("Shorten the NPC's name to " + NAME + " characters.");
        }
        final Npc.Place place = draft.place();
        if (place.world().isBlank() || place.world().length() > 64) {
            problems.add("Take a position for the NPC.");
        } else if (!Double.isFinite(place.x())
                || !Double.isFinite(place.y())
                || !Double.isFinite(place.z())
                || !Float.isFinite(place.yaw())
                || place.yaw() < -180
                || place.yaw() > 180) {
            problems.add("Take the NPC's position again: it is not a place in the world.");
        }
        if (!(draft.pitch() >= 0.5f && draft.pitch() <= 2f)) {
            problems.add("Set the typing pitch from 0.5 to 2.");
        }
        draft.equipment().forEach((slot, item) -> {
            if (!readable(item)) {
                problems.add("Take the item for "
                        + slot.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " in again: it cannot be read.");
            }
        });
        final Set<String> offered = menus.menus().stream().map(NpcMenu::key).collect(Collectors.toSet());
        problems.addAll(draft.dialogue().problems(offered));
        final Optional<NpcSkin> skin =
                draft.skin() == null ? Optional.empty() : store.skin(Objects.requireNonNull(draft.skin()));
        if (draft.skin() != null && skin.isEmpty()) {
            problems.add("Upload the skin again: it no longer exists.");
        }
        if (!problems.isEmpty()) {
            throw refused(400, "NPC_REFUSED", refusal + problems.getFirst());
        }
        return new Npc(
                        draft.id(),
                        new Dialogue.Words(en, de),
                        place,
                        draft.pose(),
                        draft.pitch(),
                        draft.equipment(),
                        draft.dialogue())
                .withSkin(skin.orElse(null));
    }

    private static boolean readable(final Npc.Equipment item) {
        if (item.item().isBlank() || item.item().length() > ITEM || item.data().isBlank()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(item.data());
            return true;
        } catch (final IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * A refusal the editor shows as it is.
     * Javalin's {@code title} holds the sentence and {@code details.code} what the editor can act on: the editor's page
     * has no texts of its own in Steward's bundle yet.
     */
    private static HttpResponseException refused(final int status, final String code, final String sentence) {
        return new HttpResponseException(status, sentence, Map.of("code", code));
    }

    private Npc found(final UUID id) {
        return store.npc(id).orElseThrow(() -> refused(404, "NO_NPC", "This NPC no longer exists. Reload the list."));
    }

    private static UUID id(final Context ctx) {
        try {
            return UUID.fromString(ctx.pathParam("id"));
        } catch (final IllegalArgumentException malformed) {
            throw new BadRequestResponse("an id is a UUID");
        }
    }

    private static <T> @Nullable T body(final Context ctx, final Class<T> type) {
        try {
            return Json.decode(ctx.body(), type);
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
    }
}
