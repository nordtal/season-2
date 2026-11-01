package eu.nordtal.season.steward.game;

import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.journal.Unjournaled;
import io.javalin.config.JavalinConfig;

/**
 * Registers the routes that ask a game server or the bot, and the game data they are drawn with.
 * The interface holds no connection to a server: each route writes or reads a request in a server's inbox.
 */
public final class GameRoutes {

    private GameRoutes() {}

    public static void register(
            final JavalinConfig cfg,
            final CommandApi commands,
            final GameActions games,
            final Inventories inventories,
            final Announcements announcements,
            final GameDataRoutes gameData,
            final ConsoleCommands consoleCommands) {
        cfg.routes.get("/api/commands/{id}", commands::outcome, Gate.KEY_HELD);
        cfg.routes.delete("/api/commands/{id}", commands::withdraw, Gate.KEY_HELD, Unjournaled.SHOWN_ONCE_SAVED);
        cfg.routes.post(
                "/api/smp/take-in",
                ctx -> GameActions.answer(ctx, commands.takeIn(ctx)),
                Gate.KEY_HELD,
                Unjournaled.SHOWN_ONCE_SAVED);

        // These routes read the same rows by their target instead of by command name.
        cfg.routes.get("/api/smp/track", games::track, Gate.KEY_HELD);
        cfg.routes.post("/api/smp/objective", games::completeObjective, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/smp/milestone", games::unlockMilestone, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/people/{id}/aura", games::setAura, Gate.KEY_HELD, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/people/{id}/spins", games::setSpins, Gate.KEY_HELD, Unjournaled.IN_THE_CHANGE);
        inventories.register(cfg);
        cfg.routes.get("/api/hunger-games/round", games::round, Gate.KEY_HELD);
        cfg.routes.post("/api/hunger-games/start", games::startRound, Gate.KEY_HELD, Unjournaled.IN_THE_CHANGE);
        cfg.routes.get("/api/announcements", announcements::list, Gate.KEY_HELD);
        cfg.routes.post("/api/announcements", announcements::create, Gate.KEY_HELD, Unjournaled.DRAFT);
        cfg.routes.put("/api/announcements/{id}", announcements::change, Gate.KEY_HELD, Unjournaled.DRAFT);
        cfg.routes.delete("/api/announcements/{id}", announcements::delete, Gate.KEY_HELD, Unjournaled.DRAFT);
        cfg.routes.post("/api/announcements/{id}/send", announcements::send, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.get("/api/game-data", gameData::read, Gate.KEY_HELD);
        cfg.routes.get("/api/game-data/{version}/icons.png", gameData::icons, Gate.KEY_HELD);
        cfg.routes.get("/api/services/{name}/commands", consoleCommands::tree, Gate.KEY_HELD);
    }
}
