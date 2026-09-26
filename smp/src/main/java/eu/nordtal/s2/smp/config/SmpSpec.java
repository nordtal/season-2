package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import java.util.List;

/**
 * {@code plugins/smp/config.yml} - everything about the SMP that is neither the track nor a database credential.
 *
 * Not the track, which is {@link MilestonesSpec}'s. Every number here is a default rather than a decision - the
 * duel loadouts, the advancement awards, the
 * "embarrassing" death causes, the wheel's prizes and weights, the start event winner's head start. Retuning any of
 * them is an edit to this file and never a release.
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  smp - the season 2 SMP",
            "-------------------------------------------------------------------",
            "The track itself is NOT here: it lives in milestones.yml and is",
            "reloaded on its own with /smp reload, because it is edited on a",
            "completely different rhythm from everything below.",
            "",
            "EVERY NUMBER IN THIS FILE IS A PROPOSAL. What was decided is the",
            "shape; the values are defaults chosen to be reasonable.",
            "",
            "Every setting can be overridden with an environment variable named",
            "NORDTAL_SMP_<PATH>, with '.' and '-' both becoming '_'."
        })
public interface SmpSpec {

    @Order(1)
    @Name("Nordtal world")
    @Key("world-nordtal")
    @Comment({
        "The permanent build world: the spawn, the tavern, the balloon, the duel platforms.",
        "",
        "Nothing is pre-generated: chunks are generated as players reach them, as in vanilla.",
        "A milestone unlock only moves the border."
    })
    @Explain(
            "The permanent world's folder name. Chunks are generated as players reach them; a milestone unlock only moves the border.")
    default String worldNordtal() {
        return "nordtal";
    }

    @Order(3)
    @Name("Nether world")
    @Key("world-nether")
    @Comment("The Nether. Fixed border, generated once before its own milestone unlocks.")
    @NoExplanationNeeded
    default String worldNether() {
        return "nordtal_nether";
    }

    @Order(4)
    @Name("End world")
    @Key("world-end")
    @Comment("The End. Entered by balloon only - a stronghold's End portal never activates.")
    @NoExplanationNeeded
    default String worldEnd() {
        return "nordtal_the_end";
    }

    @Order(6)
    @Name("Nether border diameter")
    @Key("nether-border-diameter")
    @Comment({
        "Deliberately several times larger than the 1:8 mapping requires - a 4000 overworld",
        "needs only 500 blocks of Nether to be fully reachable. It costs nothing, because",
        "Minecraft handles portal search and linking beyond a border anyway, and it leaves room",
        "for any milestone appended above 4000."
    })
    @Explain(
            "Deliberately several times larger than the 1:8 mapping requires - costs nothing, since Minecraft links portals beyond a border anyway, and leaves room for a milestone appended later.")
    default int netherBorderDiameter() {
        return 2000;
    }

    @Order(7)
    @Name("End border diameter")
    @Key("end-border-diameter")
    @Comment("The End's fixed border.")
    @NoExplanationNeeded
    default int endBorderDiameter() {
        return 2000;
    }

    @Order(8)
    @Name("Border centre X")
    @Key("border-centre-x")
    @Comment("The Nordtal border's centre.")
    @Explain(
            "Every radius mentioned elsewhere in this file - the balloon's, the spawn regions' - is measured from this point, so moving it shifts all of them at once.")
    default int borderCentreX() {
        return 106;
    }

    @Order(9)
    @Name("Border centre Z")
    @Key("border-centre-z")
    @Comment("See border-centre-x.")
    @Explain(
            "Every radius mentioned elsewhere in this file - the balloon's, the spawn regions' - is measured from this point, so moving it shifts all of them at once.")
    default int borderCentreZ() {
        return 88;
    }

    @Order(10)
    @Name("Border expansion speed (blocks per second)")
    @Key("border-expansion-blocks-per-second")
    @Comment({
        "How fast a milestone's border expansion travels - roughly a quarter to a half of",
        "walking speed, which makes the final expansion's 1550-block edge take between a",
        "quarter of an hour and half an hour to arrive. That is a ceremony rather than a",
        "hiccup, and it is meant to be."
    })
    @Explain(
            "How fast a border expansion travels; at this default the final 1550-block edge takes 15 to 30 minutes to arrive, which is a deliberate ceremony rather than a hiccup.")
    default double borderExpansionBlocksPerSecond() {
        return 1.5;
    }

    @Order(14)
    @Name("Required datapacks")
    @Key("required-datapacks")
    @Comment({
        "The world-generation datapacks that MUST be installed and enabled, checked at enable.",
        "Matched against the names Paper reports, case-insensitively, as a substring - Paper",
        "prefixes a zip with 'file/', so 'Terralith' matches 'file/Terralith_26.2_v2.6.4.zip'.",
        "",
        "THE PLUGIN ONLY CHECKS. It cannot install them, and that is not a gap: datapacks are",
        "read once at server start, into registries the whole server shares, so a pack dropped",
        "in afterwards changes nothing until the next restart. Installing them is the",
        "container entrypoint's job (deploy/minecraft/entrypoint.sh), where the version is",
        "pinned and checksummed.",
        "",
        "DATAPACKS ARE SERVER-GLOBAL and are read only from <level-name>/datapacks/. There is",
        "no per-world datapack API: DatapackManager hangs off Server, not World, and",
        "WorldCreator has no datapack option. So every world this server generates gets the",
        "same packs.",
        "",
        "Why this is worth failing the start over: a world generated without its packs is",
        "vanilla terrain permanently, because terrain is never re-rolled once it is on disk,",
        "and no world here is ever thrown away and generated again."
    })
    @Explain(
            "Checked at enable, never installed by this plugin - a world generated without these packs is vanilla terrain forever, since terrain is never re-rolled once it is on disk.")
    default List<String> requiredDatapacks() {
        return List.of("Terralith", "Dungeons and Taverns");
    }

    @Order(19)
    @Name("Balloons")
    @Key("balloons")
    @Comment({
        "Where the balloons stand. Stepping into one of these boxes opens the travel GUI; the",
        "balloon itself is a model on a barrier-block floor, and this is the volume above it.",
        "",
        "One box per world that has a balloon: Nordtal and the Nether. The End",
        "deliberately has none - it is entered by balloon and left through the vanilla exit",
        "portal, which does not work until the dragon is dead, and that one-way trip is the",
        "point of unlocking it together.",
        "",
        "NORDTAL'S BALLOON HAS A HARD CONSTRAINT AND IT IS NOT DECORATIVE: it must stand",
        "outside radius 10 and inside radius 21.5 of the border centre. That is what makes the",
        "opening border of 20 withhold travel and the first expansion to 43 hand it over. Every",
        "other social structure - tavern, NPC, both boards, both duel platforms - belongs inside",
        "radius 10, so the only thing the opening minutes withhold is the balloon. The plugin",
        "checks this at enable and refuses to start if it is wrong, because a balloon on the",
        "wrong side of that line makes the season's first milestone mean nothing.",
        "",
        "The coordinates below are placeholders."
    })
    @Explain(
            "Where the balloon boxes stand. Nordtal's has a hard constraint: it must sit outside radius 10 and inside radius 21.5 of the border centre, or the opening border of 20 stops withholding travel the way the season is designed around.")
    default List<BalloonSpec> balloons() {
        return DefaultSmp.BALLOONS;
    }

    @Order(20)
    @Name("Boards")
    @Key("boards")
    @Comment({
        "The two boards at the spawn: the current milestone at a glance, and the aura",
        "leaderboard.",
        "",
        "RENDERED PER PLAYER, IN THEIR OWN LANGUAGE. Each entry below is an anchor, not a",
        "display: the plugin spawns one Text Display per board PER VIEWER at that position and",
        "hides it from everyone else, which is the only way two people standing side by side can",
        "read the same board in two languages. With a handful of players that is a handful of",
        "entities; it is not a technique that would scale to a hundred, and it does not have to.",
        "",
        "kind is OBJECTIVE or AURA. Anything else stops the load.",
        "",
        "The coordinates are placeholders until the spawn is built."
    })
    @Explain(
            "Rendered once PER VIEWER rather than once per board - each viewer gets their own hidden Text Display so two people standing together can read it in different languages. This does not scale to a hundred players and is not meant to.")
    default List<BoardSpec> boards() {
        return DefaultSmp.BOARDS;
    }

    @Order(21)
    @Name("Duel platforms")
    @Key("duel-platforms")
    @Comment({
        "The two 3x3 platforms at the spawn. Two players standing on the same one at the same",
        "time are taken into an arena.",
        "",
        "type is SWORD or BOW and picks which loadout both fighters get. Anything else stops",
        "the load.",
        "",
        "The coordinates are placeholders until the spawn is built. Both platforms belong",
        "INSIDE radius 10 of the border centre with everything else social - the balloon is the",
        "only thing the opening border withholds."
    })
    @Explain(
            "type picks the loadout both fighters get; both platforms must sit inside radius 10 of the border centre with the rest of the social area, since the balloon alone is what the opening border withholds.")
    default List<DuelPlatformSpec> duelPlatforms() {
        return DefaultSmp.DUEL_PLATFORMS;
    }

    @Order(22)
    @Name("Duel arena base Y")
    @Key("duel-arena-base-y")
    @Comment({
        "The height of the lowest arena. Further concurrent duels stack above it.",
        "",
        "Well above anything anybody builds: the arenas are placed by the plugin and taken away",
        "again, and a stack that reached into the skyline would eventually land on somebody's",
        "tower."
    })
    @Explain(
            "Height of the lowest stacked arena, chosen well above anything players build so a full stack of concurrent duels never lands on somebody's tower.")
    default int duelArenaBaseY() {
        return 200;
    }

    @Order(23)
    @Name("Duel arena spacing")
    @Key("duel-arena-spacing")
    @Comment("Vertical distance between stacked arenas. Has to exceed the arena's own height.")
    @Explain("Must exceed the arena's own height, or stacked arenas overlap.")
    default int duelArenaSpacing() {
        return 16;
    }

    @Order(24)
    @Name("Duel arena radius")
    @Key("duel-arena-radius")
    @Comment({
        "Half the arena's floor, in blocks - a radius of 7 is a 15x15 floor. Big enough that a",
        "bow duel is not a knife fight, small enough that neither fighter can simply run."
    })
    @Explain(
            "Half the arena's floor - big enough that a bow duel is not a knife fight, small enough that neither fighter can simply run.")
    default int duelArenaRadius() {
        return 7;
    }

    @Order(25)
    @Name("Wheel regions")
    @Key("wheel-regions")
    @Comment({
        "Where the wheel of fortune stands in the tavern. Right-clicking inside one of these",
        "boxes spins it - one free spin per calendar day, plus whatever contributing to",
        "objectives has earned.",
        "",
        "Same box shape as spawn-regions and balloons, and for the same reason: a spawn is a box",
        "you may not build in, a balloon is a box that opens the travel GUI, and this is a box",
        "that spins a wheel. Three nearly identical settings would have drifted apart.",
        "",
        "IT COSTS NO AURA. Aura is recognition, not currency, and the moment it buys something",
        "it stops being recognition.",
        "",
        "The coordinates are placeholders until the tavern is built."
    })
    @Explain(
            "Boxes that spin the wheel on right-click. Spinning it never costs aura - aura is recognition, not currency, and the moment it buys something it stops being recognition.")
    default List<SpawnRegionSpec> wheelRegions() {
        return DefaultSmp.WHEEL_REGIONS;
    }

    @Order(26)
    @Name("NPC")
    @Key("npc")
    @Comment({
        "The figure in the tavern. Click it to open the objective list and hand items in.",
        "",
        "It is a MANNEQUIN - a vanilla Paper 26.2 entity with a real player skin, and no",
        "dependency at all: a Mannequin is a LivingEntity and not a Mob, so it has no AI, never",
        "despawns, never wanders and cannot be killed.",
        "",
        "skin-name is a Minecraft account whose skin the figure wears, resolved at start. Leave",
        "it empty for the default skin. A later 3D model would replace how the NPC is DRAWN and",
        "nothing about how it is clicked.",
        "",
        "The coordinates are placeholders until the tavern is built."
    })
    @Explain(
            "The clickable figure in the tavern - a Mannequin, so it has no AI, never despawns and cannot be killed. Leave skin-name empty for the default skin.")
    default NpcSpec npc() {
        return DefaultSmp.NPC;
    }

    @Order(27)
    @Name("Spawn regions")
    @Key("spawn-regions")
    @Comment({
        "The protected zones: no building, no breaking, no interaction with blocks you do not",
        "own, no explosions. A list of boxes per world, and NOT WorldGuard - what is needed is",
        "a handful of event handlers over a few fixed boxes, not a region system with claims,",
        "flags and ownership, and this avoids a large third-party dependency whose Minecraft",
        "26.2 availability is unverified.",
        "",
        "Boxes are inclusive on both corners and are checked in order; the first one that",
        "contains a block wins. THE COORDINATES BELOW ARE PLACEHOLDERS - the spawn build does",
        "not exist yet, and the one hard geometric constraint on it is that the balloon stands",
        "outside radius 10 and inside radius 21.5 of the border centre, so that border 20",
        "withholds travel and the opening expansion to 43 hands it over."
    })
    @Explain(
            "Protected boxes, checked in order with the first match winning. Deliberately not WorldGuard - avoids a large third-party dependency of unverified Minecraft 26.2 availability for what only needs a handful of fixed boxes.")
    default List<SpawnRegionSpec> spawnRegions() {
        return DefaultSmp.SPAWN_REGIONS;
    }

    @Order(28)
    @Name("Death penalty")
    @Key("death-penalty")
    @Comment({
        "What an ordinary death costs, as a POSITIVE number that is subtracted at the point of",
        "use. Anywhere except the duel arena.",
        "",
        "Aura is meant to be a number with risk in it rather than a collection meter that only",
        "ever rises. Against a season total of roughly 2480 aura in objective pots and a top",
        "contributor around 350, fifty deaths at 5 are a meaningful drag without being able to",
        "bury a hard-working player."
    })
    @Explain(
            "What an ordinary death costs, subtracted at the point of use - big enough to add real risk against a season total around 2480 aura, without being able to bury a hard-working player.")
    default int deathPenalty() {
        return 5;
    }

    @Order(29)
    @Name("Death penalty (listed causes)")
    @Key("death-penalty-listed")
    @Comment("What one of the causes below costs instead. Also a positive number.")
    @NoExplanationNeeded
    default int deathPenaltyListed() {
        return 20;
    }

    @Order(30)
    @Name("Listed death causes")
    @Key("death-causes-listed")
    @Comment({
        "The 'embarrassing' deaths, PROPOSED as a default. Damage-type keys, matched",
        "case-insensitively and with or without the minecraft: namespace.",
        "",
        "The band this list is trying to describe: a death nobody else caused and that a moment",
        "of attention would have prevented. Falling into your own lava, standing in your own",
        "fire, walking into a cactus, drowning in water you swam into, suffocating in a block",
        "you placed. Deliberately NOT here: anything a mob or another player did, anything to",
        "do with the world border or the void, and starvation - the first is not embarrassing,",
        "the second happens to everybody exploring a fresh border, and the third is usually a",
        "long trip gone wrong rather than a lapse.",
        "",
        "Dying in the End during the dragon fight stays an ORDINARY death, which is deliberate:",
        "until the dragon falls, dying is the only way home."
    })
    @Explain(
            "The 'embarrassing', self-inflicted deaths that cost the higher penalty above. Mob kills, border/void deaths and starvation are deliberately excluded, and dying to the dragon before it falls stays ordinary - it is the only way home until then.")
    default List<String> deathCausesListed() {
        return List.of(
                "lava",
                "in_fire",
                "on_fire",
                "cactus",
                "drown",
                "in_wall",
                "sweet_berry_bush",
                "hot_floor",
                "campfire",
                "stalagmite");
    }

    @Order(31)
    @Name("Duel stake")
    @Key("duel-stake")
    @Comment({
        "What a duel moves. The winner takes exactly what the loser pays, so a duel never",
        "creates or destroys aura - and the arena is the one place a death costs nothing",
        "beyond it, because the stake has already settled the fight."
    })
    @Explain(
            "What a duel moves between the two players - the winner takes exactly what the loser pays, so a duel never creates or destroys aura.")
    default int duelStake() {
        return 10;
    }

    @Order(32)
    @Name("Concurrent duel limit")
    @Key("concurrent-duel-limit")
    @Comment("How many arenas may be stacked above the spawn at once. Beyond it, players queue.")
    @NoExplanationNeeded
    default int concurrentDuelLimit() {
        return 3;
    }

    @Order(33)
    @Name("Advancement awards")
    @Key("advancement-awards")
    @Comment({
        "The advancements that pay aura, once each per player. The loader refuses anything",
        "outside the band of 2-10: above it, one advancement would outweigh a whole objective.",
        "",
        "Chosen so the number tracks how much of the game the advancement actually represents,",
        "not how hard it is to look up: 2 for the first hours, 5 for a real trip, 8 for a",
        "project, 10 for the two that take a season. This is a CURATED list and not every",
        "advancement - the point is to reward the shape of a playthrough, not to pay for",
        "ticking boxes."
    })
    @Explain(
            "Which advancements pay aura, once each per player. The loader refuses anything outside 2-10 aura so a single advancement can never outweigh a whole objective.")
    default List<AdvancementAwardSpec> advancementAwards() {
        return DefaultSmp.ADVANCEMENT_AWARDS;
    }

    @Order(35)
    @Name("Hunger Games winner aura")
    @Key("hg-winner-aura")
    @Comment({
        "The head start the start event's winner carries into the season, paid on their FIRST",
        "JOIN and never again. PROPOSED.",
        "",
        "150 is chosen against the season's own scale rather than out of the air: a top",
        "contributor finishes the whole track on roughly 350, so this is a visible head start",
        "on the leaderboard that a week of real contribution overtakes. Aura buys nothing, so",
        "the entire prize is recognition - which is also why it must not be so large that",
        "nobody can catch it."
    })
    @Explain(
            "The head start paid once on the hunger games winner's first join; sized against the season's own scale so it is a visible lead a week of real contribution can still overtake.")
    default int hgWinnerAura() {
        return 150;
    }

    @Order(36)
    @Name("Hunger Games winner items")
    @Key("hg-winner-items")
    @Comment({
        "One or two special items for the winner, also PROPOSED rather than decided. Bukkit",
        "material names with an amount.",
        "",
        "An elytra and a netherite ingot: one of them is the thing everybody wants and nobody",
        "has on day one, the other is a head start on gear that is spent the moment it is used.",
        "Neither breaks anything - there are no claims to defend and no economy to inflate."
    })
    @Explain(
            "One or two special items for the hunger games winner, proposed rather than fixed - nothing here breaks an economy, since there are no claims to defend.")
    default List<WheelPrizeSpec> hgWinnerItems() {
        return DefaultSmp.HG_WINNER_ITEMS;
    }

    @Order(37)
    @Name("Wheel extra spin chances (percent)")
    @Key("wheel-extra-spin-percents")
    @Comment({
        "The contribution shares that earn extra spins when an objective completes: one spin at",
        "the first, two at the second, three at the third. Hung off the same 2 % threshold the",
        "aura share uses, so there is one rule to understand and one place to change it."
    })
    @Explain(
            "The contribution shares that earn 1/2/3 extra wheel spins on an objective's completion - the same threshold the aura share uses, so there is one number to change rather than two.")
    default List<Integer> wheelExtraSpinPercents() {
        return List.of(2, 10, 25);
    }

    @Order(38)
    @Name("Wheel prizes")
    @Key("wheel-prizes")
    @Comment({
        "The wheel's pool and its weights, PROPOSED. Weights are relative and need not sum to",
        "anything.",
        "",
        "Three bands, and the reasoning behind the third is the one that matters: COMMON is",
        "useful and never decisive, UNCOMMON is pleasant and still ordinary, and RARE is",
        "'things you occasionally need and hate farming'. The rare band is chosen to ENCOURAGE",
        "TRADE - everybody eventually holds something good they do not need and needs something",
        "they did not draw.",
        "",
        "The wheel is the only reward channel in the design that pays out actual items, so it",
        "is the one worth abusing; the weights below make the rare band roughly one spin in",
        "twenty-five."
    })
    @Explain(
            "The wheel's pool and relative weights - the rare band is deliberately about one spin in twenty-five, sized to encourage trading rather than to be reliably farmable.")
    default List<WheelPrizeSpec> wheelPrizes() {
        return DefaultSmp.WHEEL_PRIZES;
    }

    @Order(39)
    @Name("Duel loadout: sword")
    @Key("duel-loadout-sword")
    @Comment({
        "What both players are given inside a sword duel, PROPOSED. Identical for both, from",
        "config: nobody wins by being richer.",
        "",
        "Iron rather than diamond, and no enchantments: the fight should be decided by aim and",
        "timing over about a minute, not by who lands the first critical. Sixteen golden",
        "apples' worth of healing is deliberately absent - a duel is a single fight, not a war",
        "of attrition. The player's real inventory is untouched; this is the arena's own."
    })
    @Explain(
            "What both duelists are given - identical for both, so nobody wins by being richer. No enchantments and no healing on purpose: a duel is one short fight, not a war of attrition.")
    default List<WheelPrizeSpec> duelLoadoutSword() {
        return DefaultSmp.DUEL_LOADOUT_SWORD;
    }

    @Order(40)
    @Name("Duel loadout: bow")
    @Key("duel-loadout-bow")
    @Comment({
        "The bow duel's loadout. Also PROPOSED.",
        "",
        "A plain bow, sixty-four arrows and lighter armour than the sword loadout, so that a",
        "hit matters and a miss costs. No crossbow: the reload time turns the fight into a game",
        "of cover, which is not what a 3x3 platform and a small arena are for."
    })
    @Explain(
            "The bow duel's loadout. No crossbow on purpose - its reload time turns the fight into a game of cover, which the small platform and arena are not built for.")
    default List<WheelPrizeSpec> duelLoadoutBow() {
        return DefaultSmp.DUEL_LOADOUT_BOW;
    }

    // What each feedback category sounds like lives in sounds.yml, reloadable where this file is not; see SoundsSpec.

    // admin-permissions is retired: an admin is a server operator; jcore deletes the stale key rather than a no-op.

    @Order(41)
    @Name("Admin poll interval (seconds)")
    @Key("admin-poll-interval-seconds")
    @Comment({
        "How often this server re-reads who is an admin, in seconds.",
        "",
        "An admin is a server operator for as long as they are an admin, and the flag lives in",
        "discord_user.admin - nowhere else. Read only at join, a revoked admin would keep",
        "operator until they chose to disconnect, which is the wrong direction for an emergency",
        "revocation.",
        "",
        "THIS POLL IS THE GUARANTEE, not the LISTEN connection below. A tick on which nothing",
        "changed costs one indexed query and writes nothing to ops.json, which is what makes it",
        "affordable to run for the life of the server."
    })
    @Explain(
            "How often admin status is re-read from the database - this poll is the actual guarantee a revoked admin loses operator; the LISTEN switch below only makes it feel instant.")
    default int adminPollIntervalSeconds() {
        return 30;
    }

    @Order(42)
    @Name("Listen for admin changes")
    @Key("admin-listen-enabled")
    @Comment({
        "Whether to also open a dedicated LISTEN nordtal_admin connection.",
        "",
        "It only makes a revocation feel instant instead of taking up to one poll interval; the",
        "poll above is what is actually guaranteed. Turning this off costs latency and nothing",
        "else, which is why it is a switch: it is one connection per backend, outside the pool,",
        "parked in a blocking read for the life of the server."
    })
    @Explain(
            "Makes a revocation feel instant instead of waiting for the next poll; turning it off only costs latency, since the poll above is what is actually guaranteed.")
    default boolean adminListenEnabled() {
        return true;
    }

    @Order(43)
    @Name("Balloon spawn points")
    @Key("balloon-spawn-points")
    @Comment({
        "Where the balloon PUTS A PLAYER DOWN, one point per world it flies to.",
        "",
        "Three things this is not. It is not `balloons` above - those are the boxes a player",
        "steps INTO to open the travel GUI, at the departure end. It is not the vanilla world",
        "spawn either: the balloon stopped using it on 2026-09-12, so moving a point here moves",
        "nothing else - not where a bed-less death respawns, not where /spawn goes, not what a",
        "compass points at. And it is not the first join, which has its own point below.",
        "",
        "Each point is still put through LandingSite#findSafeAt, which takes it as written",
        "whenever a player fits there and searches outwards from that column when they do not.",
        "That is what stopped the Nether balloon killing the player who took it - the Nether's",
        "world spawn is 0/66/0, which on a generated world is solid netherrack - and it stays",
        "in place for a configured point, because a typo in a Y coordinate is the same block of",
        "stone. If the search finds nothing at all the balloon REFUSES the trip and says so,",
        "which is the one thing it is allowed to do that the other callers are not.",
        "",
        "There is no world name here: which world a point belongs to is the key it sits under,",
        "so a coordinate set cannot name a world the balloon does not travel to.",
        "",
        "THE COORDINATES BELOW ARE PLACEHOLDERS - the spawn build does not exist yet."
    })
    @Explain(
            "Where the balloon actually sets a player down, one point per destination world - distinct from the boxes above (which only open the travel GUI), the vanilla world spawn, and the first-join point below.")
    default BalloonSpawnPointsSpec balloonSpawnPoints() {
        return DefaultSmp.BALLOON_SPAWN_POINTS;
    }

    @Order(44)
    @Name("First join spawn")
    @Key("first-join-spawn")
    @Comment({
        "Where a player is put down on their VERY FIRST JOIN of the season, and nowhere else.",
        "",
        "Separate from both of the above on purpose: it is not a balloon destination, and it is",
        "not the vanilla world spawn, so changing it moves the opening moment and leaves every",
        "later join, every respawn and every balloon trip exactly where they were.",
        "",
        "USED EXACTLY ONCE PER PLAYER. It rides the same one-shot claim as the season's opening",
        "pictures - smp_player.welcome_shown, taken in one statement - so a reconnect, a restart",
        "mid-welcome and two racing sessions all end with one arrival. A player with no linked",
        "Discord account has no row to claim against and is not moved at all.",
        "",
        "This one DOES name its world, because it is not tied to one of the four roles. The name",
        "has to be a world that exists; the plugin checks at start and warns if it does not, and",
        "a first join that cannot be placed leaves the player where the server spawned them.",
        "Note that it is a second place a world name is written down - if `world-nordtal` above",
        "is ever renamed, this is the line that has to move with it.",
        "",
        "The point is put through LandingSite#safeAt, which unlike the balloon's search CANNOT",
        "refuse: a first join has to end somewhere, so a point nobody fits at falls back to the",
        "point as written rather than cancelling the arrival.",
        "",
        "THE COORDINATES BELOW ARE PLACEHOLDERS - the spawn build does not exist yet."
    })
    @Explain(
            "Where a player lands on their very first join only, claimed exactly once against smp_player.welcome_shown so a reconnect or a racing session cannot double it. Separate from the balloon points and the vanilla spawn.")
    default FirstJoinSpawnSpec firstJoinSpawn() {
        return DefaultSmp.FIRST_JOIN_SPAWN;
    }

    @Order(45)
    @Name("Grave max age (hours)")
    @Key("grave-max-age-hours")
    @Comment({
        "How long a grave stands before it decays, in hours. THE CONTENTS GO WITH IT -",
        "the same as vanilla items that despawn.",
        "Nothing is dropped on the ground when a grave expires.",
        "",
        "A player who dies twice gets two graves, each with its own clock. Nothing is tidied",
        "up on their behalf.",
        "",
        "0 turns decay off and a grave stands forever, which is what this season did until",
        "2026-09-16. Negative is refused at load rather than treated as 0, because a negative",
        "age is somebody meaning something by it and getting it wrong.",
        "",
        "THE CLOCK IS THE ROW'S `created`, not a deadline written down when the grave is made.",
        "That is why it survives a restart, and it is also why lowering this number expires",
        "graves that already stand rather than only new ones. Raising it brings nothing back:",
        "an expired grave is deleted, not hidden."
    })
    @Explain("How long a grave stands before it and everything in it decays, in hours. 0 never decays.")
    default int graveMaxAgeHours() {
        return 24;
    }
}
