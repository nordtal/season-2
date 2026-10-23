package eu.nordtal.season.steward.game;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.mode.ModeStateStore;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.mode.ModeStateStore.Status;
import eu.nordtal.season.database.online.OnlinePlayer;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.Refusal;
import eu.nordtal.season.messages.Refused;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Shows a player's Survival and Creative states on every server and removes stacks from them.
 * A state a server holds is that server's to change, so Steward asks it; any other Steward changes in the database.
 */
public final class Inventories {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** How long Steward waits for the server that holds a state. */
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final Data data;
    private final GameActions games;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final Waiting clock;

    public Inventories(
            final Data data,
            final GameActions games,
            final Function<Context, DiscordAuth.Account> accounts,
            final Waiting clock) {
        this.data = data;
        this.games = games;
        this.accounts = accounts;
        this.clock = clock;
    }

    public void register(final JavalinConfig cfg) {
        cfg.routes.get("/api/people/{id}/inventories", this::read, Gate.KEY_HELD);
        cfg.routes.post(
                "/api/people/{id}/inventories/remove",
                this::remove,
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.REMOVE_STACK));
    }

    private ModeStateStore store() {
        return ModeStateStore.using(data.dataSource());
    }

    /** One stack. Slots 0 to 8 of the inventory are the hotbar, 9 to 35 the rest, 36 to 39 armour, 40 the offhand. */
    public record InventoryStack(Container container, int slot, String item, int amount) {}

    /**
     * One state of a player on one server.
     *
     * @param progress the progress towards the next level, from 0 to 1
     * @param saved when the row was written; a held state may have changed since
     */
    public record InventoryState(
            String server,
            Mode mode,
            Status status,
            int level,
            double progress,
            Instant saved,
            List<InventoryStack> stacks) {}

    /** @param online the server the player is on, absent while they are on none */
    public record PlayerInventories(
            boolean linked, @Nullable String online, List<InventoryState> states) {}

    /** The stack Steward showed, which a removal names so a changed slot is refused. */
    public record StackRemoval(String server, Mode mode, Container container, int slot, String item, int amount) {}

    /** {@code GET /api/people/{id}/inventories} asks the player's server for its copy first, then reads every state. */
    public void read(final Context ctx) {
        final Optional<UUID> linked = data.access().linkedMinecraftAccount(games.person(ctx));
        if (linked.isEmpty()) {
            ctx.json(new PlayerInventories(false, null, List.of()));
            return;
        }
        final PlayerId player = PlayerId.of(linked.get());
        final @Nullable String online = server(linked.get());
        // A server that does not answer leaves its copy as of saved, which the page shows.
        ask(ctx, online, new SmpRequest.StoreModeState(player), new HungerGamesRequest.StoreModeState(player));
        ctx.json(new PlayerInventories(
                true,
                online,
                store().states(player).stream().map(Inventories::state).toList()));
    }

    /**
     * {@code POST /api/people/{id}/inventories/remove} removes the stack the body names, if the slot still holds it.
     */
    public void remove(final Context ctx) {
        final DiscordId person = games.person(ctx);
        final StackRemoval body = body(ctx);
        final PlayerId player = PlayerId.of(data.access()
                .linkedMinecraftAccount(person)
                .orElseThrow(() -> new RequestRefused(409, ANSWER.inventoryUnlinked())));
        switch (store().remove(
                        body.server(),
                        player,
                        body.mode(),
                        body.container(),
                        body.slot(),
                        body.item(),
                        body.amount())) {
            case REMOVED -> {}
            case CHANGED -> throw refused(ServerRefusal.STACK_CHANGED.with());
            case HELD -> {
                final Request<?> answer = ask(
                                ctx,
                                body.server(),
                                new SmpRequest.RemoveStack(
                                        player, body.mode(), body.container(), body.slot(), body.item(), body.amount()),
                                new HungerGamesRequest.RemoveStack(
                                        player, body.mode(), body.container(), body.slot(), body.item(), body.amount()))
                        .orElseThrow(() -> new RequestRefused(
                                409,
                                isAsked(body.server())
                                        ? ANSWER.serverSilent(body.server())
                                        : ANSWER.inventoryHeld(body.server())));
                answer.refusal().ifPresent(refusal -> {
                    throw refused(refusal);
                });
            }
        }
        Journaling.line(ctx, person, new JournalDetail.StackRemoved(body.item(), body.amount()));
        ctx.status(204);
    }

    private static StackRemoval body(final Context ctx) {
        final @Nullable StackRemoval body = ctx.bodyAsClass(StackRemoval.class);
        if (body == null
                || body.server() == null
                || body.mode() == null
                || body.container() == null
                || body.item() == null
                || body.slot() < 0
                || body.amount() < 1) {
            throw new BadRequestResponse("a removal names the server, mode, container, slot, item and amount shown");
        }
        return body;
    }

    /** Returns the server the roster has the player on. */
    private @Nullable String server(final UUID player) {
        return data.roster().current().stream()
                .filter(online -> online.uuid().equals(player))
                .map(OnlinePlayer::subject)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Whether Steward can ask {@code server}; limbo has no inbox. */
    private static boolean isAsked(final @Nullable String server) {
        return Topology.SMP.equals(server) || Topology.HUNGER_GAMES.equals(server);
    }

    /** Asks {@code server} and returns its answer, empty when Steward cannot ask it or it did not answer in time. */
    private Optional<? extends Request<?>> ask(
            final Context ctx,
            final @Nullable String server,
            final SmpRequest smp,
            final HungerGamesRequest hungerGames) {
        if (!isAsked(server)) {
            return Optional.empty();
        }
        final Actor who = accounts.apply(ctx).actor();
        return Topology.SMP.equals(server)
                ? data.smp().ask(smp, who, PATIENCE, clock)
                : data.hungerGames().ask(hungerGames, who, PATIENCE, clock);
    }

    private static InventoryState state(final ModeStateStore.Stored stored) {
        return new InventoryState(
                stored.server(),
                stored.mode(),
                stored.status(),
                stored.state().level(),
                stored.state().experience(),
                stored.saved(),
                stored.state().stacks().stream()
                        .map(stack -> new InventoryStack(stack.container(), stack.slot(), stack.item(), stack.amount()))
                        .toList());
    }

    private static Refused refused(final Refusal refusal) {
        return new Refused(refusal.reason(), refusal.message());
    }
}
