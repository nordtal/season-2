package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.smp.board.BoardFrame;
import eu.nordtal.s2.smp.prestige.Prestige;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Where {@code smp}'s config files live, and every rule about what a valid value is.
 *
 * The track, sounds, colours and prestige files are separate so {@code /smp reload} can re-read them.
 */
public final class Configs {

    private Configs() {}

    public static ConfigHandle<SmpSpec> load(final Path dataFolder, final Logger logger) throws ConfigException {
        return load(dataFolder, logger, "config", SmpSpec.class, "NORDTAL_SMP", Configs::validate, true);
    }

    public static ConfigHandle<DatabaseSpec> database(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(
                dataFolder,
                logger,
                "database",
                DatabaseSpec.class,
                "NORDTAL_SMP_DATABASE",
                config -> {
                    requireText("jdbc-url", config.jdbcUrl());
                    requireText("username", config.username());
                    if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                        throw new IllegalArgumentException(
                                "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
                    }
                    requirePositive("maximum-pool-size", config.maximumPoolSize());
                    requirePositive("query-timeout-seconds", config.queryTimeoutSeconds());
                },
                false);
    }

    /**
     * Loads the track, validating only its structure; its names and stored progress are checked where it is taken.
     */
    public static ConfigHandle<MilestonesSpec> milestones(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(
                dataFolder,
                logger,
                "milestones",
                MilestonesSpec.class,
                "NORDTAL_SMP_MILESTONES",
                config -> {
                    final Milestones.Result result = Milestones.read(config);
                    if (!result.problems().isEmpty()) {
                        throw new IllegalArgumentException("the milestone track is not usable:\n" + result.describe());
                    }
                },
                false);
    }

    /** Loads the sounds, with no validator: {@code FeedbackSounds} corrects or silences a bad entry. */
    public static ConfigHandle<SoundsSpec> sounds(final Path dataFolder, final Logger logger) throws ConfigException {
        return load(dataFolder, logger, "sounds", SoundsSpec.class, "NORDTAL_SMP_SOUNDS", config -> {}, false);
    }

    /** Loads the tone colours, with no validator: {@code ToneColours#parse} corrects a bad hex value. */
    public static ConfigHandle<ColoursSpec> colours(final Path dataFolder, final Logger logger) throws ConfigException {
        return load(dataFolder, logger, "colours", ColoursSpec.class, "NORDTAL_SMP_COLOURS", config -> {}, false);
    }

    /**
     * {@code ColoursSpec}'s five accessors, as the map {@link eu.nordtal.s2.messagerendering.ToneColours} parses.
     *
     * An exhaustive {@code switch}, so a sixth {@link Tone} does not compile until its colour is named.
     */
    public static Map<Tone, String> declared(final ColoursSpec spec) {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        for (final Tone tone : Tone.values()) {
            declared.put(
                    tone,
                    switch (tone) {
                        case GOOD -> spec.good();
                        case BAD -> spec.bad();
                        case WARN -> spec.warn();
                        case NEUTRAL -> spec.neutral();
                        case MUTED -> spec.muted();
                    });
        }
        return declared;
    }

    /**
     * Loads the crest ladder, refusing only bad hours, since {@link Prestige}'s constructor is the whole rule for them.
     */
    public static ConfigHandle<PrestigeSpec> prestige(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(
                dataFolder,
                logger,
                "prestige",
                PrestigeSpec.class,
                "NORDTAL_SMP_PRESTIGE",
                config -> new Prestige(declaredPrestigeHours(config)),
                false);
    }

    /**
     * The thirteen tier colours, in tier order, as {@link eu.nordtal.s2.smp.prestige.PrestigeColours#parse} takes them.
     */
    public static List<String> declaredPrestigeTiers(final PrestigeSpec spec) {
        final PrestigeSpec.TierColoursSpec tiers = spec.colours();
        return List.of(
                tiers.tier01(),
                tiers.tier02(),
                tiers.tier03(),
                tiers.tier04(),
                tiers.tier05(),
                tiers.tier06(),
                tiers.tier07(),
                tiers.tier08(),
                tiers.tier09(),
                tiers.tier10(),
                tiers.tier11(),
                tiers.tier12(),
                tiers.tier13());
    }

    /** The thirteen tier hours, in the same order, as {@link Prestige} takes them. */
    public static List<Integer> declaredPrestigeHours(final PrestigeSpec spec) {
        final PrestigeSpec.TierHoursSpec tiers = spec.hours();
        return List.of(
                tiers.tier01(),
                tiers.tier02(),
                tiers.tier03(),
                tiers.tier04(),
                tiers.tier05(),
                tiers.tier06(),
                tiers.tier07(),
                tiers.tier08(),
                tiers.tier09(),
                tiers.tier10(),
                tiers.tier11(),
                tiers.tier12(),
                tiers.tier13());
    }

    private static void validate(final SmpSpec config) {
        // Whether the world exists is checked at enable, after {@code Worlds#bootstrap} creates it.
        requireText("world-nordtal", config.worldNordtal());
        requireText("first-join-spawn: world", config.firstJoinSpawn().world());
        // {@code AdminWatch} floors this at one second.
        requirePositive("admin-poll-interval-seconds", config.adminPollIntervalSeconds());
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
        if (config.graveMaxAgeHours() < 0) {
            // Zero means "never decays", so it cannot double as the error case.
            throw new IllegalArgumentException(
                    "grave-max-age-hours must not be negative. 0 is how decay is turned off; a "
                            + "negative number is not a shorter way of saying that");
        }
        requirePositive("concurrent-duel-limit", config.concurrentDuelLimit());

        validateAdvancementAwards(config);
        validateWheelPrizes(config);
        validateBoards(config);
        validateSpawnRegions(config);
    }

    private static void validateAdvancementAwards(final SmpSpec config) {
        for (final AdvancementAwardSpec award : config.advancementAwards()) {
            if (award.advancement() == null || award.advancement().isBlank()) {
                throw new IllegalArgumentException("advancement-awards: every entry needs an advancement");
            }
            if (award.aura() < 2 || award.aura() > 10) {
                throw new IllegalArgumentException(
                        "advancement-awards: '" + award.advancement() + "' pays " + award.aura()
                                + " aura; the band is 2-10, so that one advancement cannot outweigh "
                                + "a whole objective");
            }
        }
    }

    private static void validateWheelPrizes(final SmpSpec config) {
        if (config.wheelPrizes() == null || config.wheelPrizes().isEmpty()) {
            throw new IllegalArgumentException(
                    "wheel-prizes must not be empty; the wheel has to have " + "something to land on");
        }
        for (final WheelPrizeSpec prize : config.wheelPrizes()) {
            requireText("wheel-prizes: item", prize.item());
            requirePositive("wheel-prizes: weight for '" + prize.item() + "'", prize.weight());
            requirePositive("wheel-prizes: amount for '" + prize.item() + "'", prize.amount());
        }
    }

    private static void validateBoards(final SmpSpec config) {
        for (final BoardSpec board : config.boards()) {
            requireText("boards: world", board.world());
            if (board.width() < BoardFrame.MIN_WIDTH || board.width() > BoardFrame.MAX_WIDTH) {
                throw new IllegalArgumentException(
                        "boards: '" + board.kind() + "' is " + board.width() + " pixels wide; the "
                                + "frame's shifts reach " + BoardFrame.MIN_WIDTH + " to "
                                + BoardFrame.MAX_WIDTH + ", and a width outside that would throw on "
                                + "the first render rather than here");
            }
        }
    }

    private static void validateSpawnRegions(final SmpSpec config) {
        for (final SpawnRegionSpec region : config.spawnRegions()) {
            requireText("spawn-regions: world", region.world());
            if (region.maxX() < region.minX() || region.maxY() < region.minY() || region.maxZ() < region.minZ()) {
                throw new IllegalArgumentException("spawn-regions: the box in '" + region.world()
                        + "' has a max corner that is " + "not above its min corner");
            }
        }
    }

    private static <T> ConfigHandle<T> load(
            final Path dataFolder,
            final Logger logger,
            final String name,
            final Class<T> specType,
            final String envPrefix,
            final ConfigValidator<T> validator,
            final boolean defaultsArePlaceholders)
            throws ConfigException {
        final Path file = dataFolder.resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            // Only config.yml's defaults are placeholders; warning about the rest trains operators to ignore WARN.
            if (defaultsArePlaceholders) {
                logger.warn(
                        "No config existed at {} - defaults were written and are almost "
                                + "certainly not what you want, especially the world names and every "
                                + "coordinate",
                        file.toAbsolutePath());
            } else {
                logger.info(
                        "No config existed at {} - it was written with this project's "
                                + "defaults, which are usable as they stand",
                        file.toAbsolutePath());
            }
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    /** Writes {@code handle}'s environment overrides next to its file, for steward-worker, best-effort. */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle, final Logger logger) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
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
