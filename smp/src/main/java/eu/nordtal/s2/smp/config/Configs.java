package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.hud.BoardFrame;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where {@code smp}'s config files live, and every rule about what a valid value is.
 *
 * <p>Four files, each with its own environment namespace, in the shape every other module in this
 * repository uses: {@code config.yml} for the settings, {@code database.yml} for the connection,
 * {@code milestones.yml} for the track and {@code sounds.yml} for the feedback sounds.
 *
 * <h2>Why the track and the sounds are their own files</h2>
 * Both are edited on a completely different rhythm from everything else, and both are edited
 * <em>while players are online</em>: a milestone is appended mid-season in response to a track
 * finishing early, and a sound is retuned the first evening somebody says it is irritating. Keeping
 * them out of {@code config.yml} means {@code /smp reload} can re-read either without also
 * re-reading a duel loadout, a world name or a database password - none of which the plugin would
 * notice changing, because it binds them once at enable. It also means the diff of a track change
 * is a diff of the track.
 */
public final class Configs {

    private Configs() {
    }

    public static @NotNull ConfigHandle<SmpSpec> load(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(dataFolder, logger, "config", SmpSpec.class, "NORDTAL_SMP", Configs::validate);
    }

    public static @NotNull ConfigHandle<DatabaseSpec> database(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(dataFolder, logger, "database", DatabaseSpec.class, "NORDTAL_SMP_DATABASE", config -> {
            requireText("jdbc-url", config.jdbcUrl());
            requireText("username", config.username());
            if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
            }
            requirePositive("maximum-pool-size", config.maximumPoolSize());
            requirePositive("query-timeout-seconds", config.queryTimeoutSeconds());
        });
    }

    /**
     * Loads the track.
     *
     * <p>Only the <em>structure</em> is validated here, by {@link Milestones#read}: keys, types,
     * targets, the one participation gate per milestone, and which fields belong to which type.
     * Whether an item name, a statistic or an advancement exists is <b>not</b> checked, because
     * resolving any of them needs an initialised Bukkit registry - the plugin binds them once at
     * enable and refuses to start on one it cannot resolve, which fails just as fast and says which
     * name it was.
     *
     * <p>Neither is the track compared to the stored progress here; that is
     * {@code TrackValidation}'s, and it needs the database this method has no business opening.
     */
    public static @NotNull ConfigHandle<MilestonesSpec> milestones(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(dataFolder, logger, "milestones", MilestonesSpec.class, "NORDTAL_SMP_MILESTONES",
                config -> {
                    final Milestones.Result result = Milestones.read(config);
                    if (!result.problems().isEmpty()) {
                        throw new IllegalArgumentException(
                                "the milestone track is not usable:\n" + result.describe());
                    }
                });
    }

    /**
     * Loads the sounds.
     *
     * <p><b>No validator.</b> Every rule about a sound is enforced where it is parsed, in
     * {@code FeedbackSounds}, and every one of them corrects or silences rather than refusing: a
     * typo in a chime must not be the reason a season is offline. Refusing here would put that
     * decision in the one place that can only answer by stopping the server.
     */
    public static @NotNull ConfigHandle<SoundsSpec> sounds(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(dataFolder, logger, "sounds", SoundsSpec.class, "NORDTAL_SMP_SOUNDS",
                config -> { });
    }

    private static void validate(final SmpSpec config) {
        requireText("world-nordtal", config.worldNordtal());
        requireText("world-farm", config.worldFarm());
        requirePositive("farm-world-border-diameter", config.farmWorldBorderDiameter());
        requirePositive("nether-border-diameter", config.netherBorderDiameter());
        requirePositive("end-border-diameter", config.endBorderDiameter());
        requirePositive("border-expansion-blocks-per-second", config.borderExpansionBlocksPerSecond());

        if (config.deathPenalty() < 0 || config.deathPenaltyListed() < 0) {
            throw new IllegalArgumentException(
                    "death penalties are configured as positive numbers and subtracted at the point "
                            + "of use, so neither may be negative");
        }
        if (config.duelStake() < 0) {
            throw new IllegalArgumentException("duel-stake must not be negative");
        }
        requirePositive("concurrent-duel-limit", config.concurrentDuelLimit());

        if (config.prestigeThresholdHours() == null || config.prestigeThresholdHours().size() != 13) {
            throw new IllegalArgumentException(
                    "prestige-threshold-hours must have exactly 13 entries - the resource pack draws "
                            + "thirteen crest designs and a fourteenth tier would have nothing to "
                            + "render as");
        }
        // The rest of the threshold rules - starting at zero, rising strictly - live in Prestige's
        // own constructor, so that a caller who builds one by hand gets the same guarantees. Run it
        // here so a bad list stops the load rather than the first render.
        new eu.nordtal.s2.smp.prestige.Prestige(config.prestigeThresholdHours());

        for (final SmpSpec.AdvancementAwardSpec award : config.advancementAwards()) {
            if (award.advancement() == null || award.advancement().isBlank()) {
                throw new IllegalArgumentException("advancement-awards: every entry needs an advancement");
            }
            if (award.aura() < 2 || award.aura() > 10) {
                throw new IllegalArgumentException(
                        "advancement-awards: '" + award.advancement() + "' pays " + award.aura()
                                + " aura; docs/smp.md sets the band at 2-10, and a value outside it "
                                + "is what would let one advancement outweigh a whole objective");
            }
        }

        if (config.wheelPrizes() == null || config.wheelPrizes().isEmpty()) {
            throw new IllegalArgumentException("wheel-prizes must not be empty; the wheel has to have "
                    + "something to land on");
        }
        for (final SmpSpec.WheelPrizeSpec prize : config.wheelPrizes()) {
            requireText("wheel-prizes: item", prize.item());
            requirePositive("wheel-prizes: weight for '" + prize.item() + "'", prize.weight());
            requirePositive("wheel-prizes: amount for '" + prize.item() + "'", prize.amount());
        }

        for (final SmpSpec.BoardSpec board : config.boards()) {
            requireText("boards: world", board.world());
            if (board.width() < BoardFrame.MIN_WIDTH || board.width() > BoardFrame.MAX_WIDTH) {
                throw new IllegalArgumentException(
                        "boards: '" + board.kind() + "' is " + board.width() + " pixels wide; the "
                                + "frame's shifts reach " + BoardFrame.MIN_WIDTH + " to "
                                + BoardFrame.MAX_WIDTH + ", and a width outside that would throw on "
                                + "the first render rather than here");
            }
        }

        for (final SmpSpec.SpawnRegionSpec region : config.spawnRegions()) {
            requireText("spawn-regions: world", region.world());
            if (region.maxX() < region.minX() || region.maxY() < region.minY()
                    || region.maxZ() < region.minZ()) {
                throw new IllegalArgumentException(
                        "spawn-regions: the box in '" + region.world() + "' has a max corner that is "
                                + "not above its min corner");
            }
        }
    }

    private static <T> ConfigHandle<T> load(final Path dataFolder, final Logger logger, final String name,
                                            final Class<T> specType, final String envPrefix,
                                            final ConfigValidator<T> validator) throws ConfigException {
        final Path file = dataFolder.resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            logger.warn("No config existed at {} - defaults were written and are almost certainly "
                    + "not what you want, especially the world names and every coordinate",
                    file.toAbsolutePath());
        }
        return handle;
    }

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    private static void requirePositive(final String key, final double value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }
}
