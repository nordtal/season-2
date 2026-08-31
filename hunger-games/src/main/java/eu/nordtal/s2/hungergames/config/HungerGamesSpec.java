package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;

import java.util.List;

/**
 * {@code config/config.yml} - everything the hunger games start event needs that is not a
 * database credential. See {@code docs/hunger-games.md} for the concept this implements.
 * <p>
 * <b>The hard minimum of two participants is not here.</b> It is {@link #HARD_MINIMUM_PARTICIPANTS},
 * a constant, because it is arithmetic and not taste: the border step is
 * {@code (borderStartDiameter - borderEndDiameter) / (participants - 1)}, which divides by zero at
 * one participant. {@link #softMinimumParticipants()} is the configurable one - the point below
 * which the game is not worth playing, but a start is still allowed after a confirmation
 * (see {@code docs/hunger-games.md#start}).
 * </p>
 * <p>
 * <b>World-data coordinates get real defaults; nothing Discord/guild-shaped does.</b> The lobby
 * box, the loot points and the spawn tower ring are all part of a hand-built world folder that
 * does not exist in this repository yet (docs/hunger-games.md, "World rules"). Their defaults are
 * therefore placeholders an operator fills in once the world is built - not secrets, so unlike a
 * Discord role or channel id there is nothing wrong with shipping a real-looking number here.
 * </p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  hunger-games - the season 2 start event",
        "-------------------------------------------------------------------",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_HUNGER_GAMES_<PATH>, with '.' and '-' both becoming",
        "'_':",
        "",
        "  countdown-seconds  ->  NORDTAL_HUNGER_GAMES_COUNTDOWN_SECONDS",
        "",
        "The environment wins over this file and is never written back into",
        "it. A setting this file does not declare stops the plugin from",
        "starting rather than being ignored.",
        "",
        "The loot point and lobby coordinates are placeholders: the actual",
        "event world (docs/hunger-games.md#world-rules) is hand-built and",
        "does not exist in this repository yet. Fill them in once it does."
})
public interface HungerGamesSpec {

    /**
     * The hard floor below which {@code /hg start} refuses outright. Not configurable: it is the
     * point at which {@code step = (borderStartDiameter - borderEndDiameter) / (participants - 1)}
     * divides by zero, so a lower value is not "strict", it is broken. See
     * {@code docs/hunger-games.md#start}.
     */
    int HARD_MINIMUM_PARTICIPANTS = 2;

    @Order(1)
    @Key("countdown-seconds")
    @Comment("How long players are frozen on their spawn towers before release.")
    default int countdownSeconds() {
        return 60;
    }

    @Order(2)
    @Key("soft-minimum-participants")
    @Comment({
            "Below this many effective (post-demotion) participants, /hg start asks for",
            "confirmation instead of starting outright - a rehearsal with a handful of real",
            "clients is exactly what docs/hunger-games.md#verification demands, and it must not",
            "be blocked by a rule meant to catch a mis-click. The hard floor of "
                    + HARD_MINIMUM_PARTICIPANTS + " below which",
            "the command refuses outright is arithmetic, not configurable - see",
            "HungerGamesSpec#HARD_MINIMUM_PARTICIPANTS."
    })
    default int softMinimumParticipants() {
        return 4;
    }

    @Order(3)
    @Key("border-start-diameter")
    @Comment("The world border's diameter at the start of the game, in blocks.")
    default double borderStartDiameter() {
        return 250.0;
    }

    @Order(4)
    @Key("border-end-diameter")
    @Comment("The floor the border shrinks to and never passes, in blocks.")
    default double borderEndDiameter() {
        return 1.0;
    }

    @Order(5)
    @Key("border-wall-speed-blocks-per-second")
    @Comment({
            "How fast the border WALL moves once a death-triggered shrink starts, in diameter-",
            "blocks per second of DIAMETER change - Minecraft's WorldBorder#setSize animates the",
            "diameter, and the wall itself moves at half that rate, so this value is already",
            "doubled from the wall speed docs/hunger-games.md asks for.",
            "",
            "'Just under walking speed' (docs/hunger-games.md#the-border): a player's walk speed",
            "is 4.317 blocks/s, so the wall must move under that. The default here is 6.0",
            "diameter-blocks/s, i.e. a 3.0 blocks/s wall - about 70% of walking speed. Under the",
            "theoretical ceiling of double walking speed (~8.6) on purpose: at exactly walking",
            "speed a straight-line escape leaves no margin at all for a player who has to dodge",
            "terrain or another player, and this event has both. Tune down further if playtesting",
            "shows the border still feels impossible to outrun in practice."
    })
    default double borderWallSpeedBlocksPerSecond() {
        return 6.0;
    }

    @Order(6)
    @Key("border-quiet-period-seconds")
    @Comment({
            "How long the game can go with no death before the passive shrink kicks in.",
            "",
            "Not decided by docs/hunger-games.md - it explicitly leaves this number open for an",
            "implementation session to propose (see that file's 'Still open' section). Default:",
            "600 seconds (10 minutes). Reasoning: this event runs for hours (the loot schedule's",
            "own default reaches 2h30 - see RefillTierSpec), so a quiet period has to be long",
            "enough that ordinary lulls in the fighting - looting, travelling between points,",
            "waiting out another fight - do not trigger a shrink that then fights the very next",
            "death-triggered one. Ten minutes is long relative to a fight (seconds to low minutes)",
            "and short relative to the whole event (hours), which is what makes it 'a nicety that",
            "resolves the two dead ends' rather than a constant pressure."
    })
    default int borderQuietPeriodSeconds() {
        return 600;
    }

    @Order(7)
    @Key("border-passive-shrink-blocks-per-hour")
    @Comment({
            "How fast the border shrinks during a passive (quiet-period) shrink, in diameter-",
            "blocks per hour - deliberately a much coarser unit than the death-triggered wall",
            "speed above, because this is meant to be barely noticeable minute to minute.",
            "",
            "Also not decided by docs/hunger-games.md; proposed here. Default: 15 diameter-",
            "blocks/hour, i.e. 0.25 blocks/minute of diameter, an order of magnitude slower than",
            "the death-triggered shrink (6.0 diameter-blocks/SECOND). Reasoning: this only has to",
            "be fast enough that a field of stalemated disconnected bodies or a same-team final",
            "two eventually gets forced together - it does not have to feel like pressure while",
            "real fighting is already happening elsewhere, which is what a death-triggered shrink",
            "is for. It is cancelled the instant a death resumes the death-triggered shrink, so",
            "it never has to double as the 'real' shrink rate."
    })
    default double borderPassiveShrinkBlocksPerHour() {
        return 15.0;
    }

    @Order(8)
    @Key("pvp-protection-seconds")
    @Comment("How long after release everyone is protected from everyone, per docs/hunger-games.md#start.")
    default int pvpProtectionSeconds() {
        return 60;
    }

    @Order(9)
    @Key("spawn-tower-radius")
    @Comment("Distance from world spawn each spawn tower is placed at, in blocks.")
    default double spawnTowerRadius() {
        return 100.0;
    }

    @Order(10)
    @Key("spawn-tower-height")
    @Comment("How far above the world's spawn Y level the tower platforms sit, in blocks.")
    default double spawnTowerHeight() {
        return 4.0;
    }

    @Order(11)
    @Key("world-name")
    @Comment({
            "The Bukkit world name the event runs in. Not a snowflake - a world folder name, so it",
            "gets a real (placeholder) default like any other id that is not Discord-shaped."
    })
    default String worldName() {
        return "hunger_games";
    }

    @Order(12)
    @Key("lobby")
    @Comment("The lobby box: its teleport point, the rules/map image grid, and the ready broadcast.")
    LobbySpec lobby();

    @Order(13)
    @Key("loot-points")
    @Comment({
            "Five loot points: the spawn plus four staggered locations, per",
            "docs/hunger-games.md#loot. World-data, not secrets - see this interface's own",
            "documentation for why coordinates default to real (placeholder) numbers.",
            "",
            "The list may not be empty and must contain exactly 5 entries with unique labels; see",
            "Configs' validator. If you have emptied it, this is the shape:",
            "",
            "  loot-points:",
            "  - label: spawn",
            "    x: 0.0",
            "    y: 64.0",
            "    z: 0.0"
    })
    default List<LootPointSpec> lootPoints() {
        return DefaultLootPoints.LIST;
    }

    @Order(14)
    @Key("refill-tiers")
    @Comment({
            "The loot refill schedule: how long after the start each tier's restock happens, and",
            "what items it stocks every loot point with. A list, so a schedule change is a config",
            "edit and not a release - see docs/hunger-games.md#loot for the agreed default",
            "schedule (basic at 0h, iron-level PvP gear at 1h, diamond-level at 2h, overpowered at",
            "2h30) and its own note that the pool CONTENTS were explicitly left for an",
            "implementation session to propose; DefaultRefillTiers is that proposal.",
            "",
            "The list may not be empty and delays must be unique and ascending; see Configs'",
            "validator. A tier is identified by its delay, so changing 'delay-minutes' on an",
            "existing entry retires that tier."
    })
    default List<RefillTierSpec> refillTiers() {
        return DefaultRefillTiers.LIST;
    }

    @Reload
    void reload();

    /** The lobby box: teleport point, map/rules image grid, and the periodic ready broadcast. */
    @ConfigSpec
    interface LobbySpec {

        @Order(1)
        @Key("x")
        @Comment("Lobby teleport point, world coordinates.")
        default double x() {
            return 0.0;
        }

        @Order(2)
        @Key("y")
        @Comment("Lobby teleport point, world coordinates.")
        default double y() {
            return 200.0;
        }

        @Order(3)
        @Key("z")
        @Comment("Lobby teleport point, world coordinates.")
        default double z() {
            return 0.0;
        }

        @Order(4)
        @Key("broadcast-interval-seconds")
        @Comment("How often the ready-check broadcast with its clickable ready button repeats.")
        default int broadcastIntervalSeconds() {
            return 300;
        }

        @Order(5)
        @Key("map-grid-columns")
        @Comment({
                "How many Minecraft maps wide the sliced lobby image grid is. The image is sliced",
                "from hunger-games/src/main/resources/lobby/map-<lang>.png (one per language, see",
                "docs/i18n.md). 3x3 (384x384px) was decided 2026-08-31 alongside the dummy",
                "map-en.png/map-de.png placeholders this default now matches - a missing file is",
                "logged and skipped, not a startup failure, since the real artwork is still a",
                "design task."
        })
        default int mapGridColumns() {
            return 3;
        }

        @Order(6)
        @Key("map-grid-rows")
        @Comment("How many Minecraft maps tall the sliced lobby image grid is.")
        default int mapGridRows() {
            return 3;
        }

        @Order(7)
        @Key("map-frame-origin-x")
        @Comment({
                "World coordinates of the top-left item frame's block position in the map grid.",
                "The grid extends along +X (columns) and +Y downward (rows); frames must already",
                "exist at these positions in the hand-built lobby - this plugin only sets each",
                "frame's map item, it does not place frames."
        })
        default int mapFrameOriginX() {
            return 0;
        }

        @Order(8)
        @Key("map-frame-origin-y")
        @Comment("World coordinates of the top-left item frame's block position in the map grid.")
        default int mapFrameOriginY() {
            return 196;
        }

        @Order(9)
        @Key("map-frame-origin-z")
        @Comment("World coordinates of the top-left item frame's block position in the map grid.")
        default int mapFrameOriginZ() {
            return 0;
        }
    }

    /** One of the five loot points: a label used in the HUD and announcements, and its position. */
    @ConfigSpec
    interface LootPointSpec {

        @Order(1)
        @Key("label")
        @Comment("A short identifying label, shown in refill announcements. Must be unique.")
        default String label() {
            return "spawn";
        }

        @Order(2)
        @Key("x")
        @Comment("World coordinates.")
        default double x() {
            return 0.0;
        }

        @Order(3)
        @Key("y")
        @Comment("World coordinates.")
        default double y() {
            return 64.0;
        }

        @Order(4)
        @Key("z")
        @Comment("World coordinates.")
        default double z() {
            return 0.0;
        }
    }

    /** One refill: when it happens, and what it stocks every loot point's chest with. */
    @ConfigSpec
    interface RefillTierSpec {

        @Order(1)
        @Key("delay-minutes")
        @Comment("Minutes after the game's release (end of countdown) this refill happens.")
        default int delayMinutes() {
            return 0;
        }

        @Order(2)
        @Key("items")
        @Comment({
                "The item pool for this refill, as a list of Bukkit material names. Every loot",
                "chest is cleared and restocked with one of each - see LootRefill. Material names",
                "are validated at load; an unknown one fails the load with the name that is wrong."
        })
        default List<String> items() {
            return List.of("BREAD");
        }
    }
}
