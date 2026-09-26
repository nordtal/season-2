package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import eu.nordtal.s2.common.hud.BoardFrame;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.smp.prestige.Prestige;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Where {@code smp} 's config files live, and every rule about what a valid value is.
 *
 * Four files, each with its own environment namespace, in the shape every other module in this repository uses:
 * {@code config.yml} for the settings, {@code database.yml} for the connection, {@code milestones.yml} for the track
 * and {@code sounds.yml} for the feedback sounds.
 *
 * The track and the sounds are their own files because both are edited while players are online: {@code /smp reload}
 * re-reads either without touching a duel loadout, a world name or a database password, none of which the plugin
 * would notice changing - it binds them once at enable.
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
     * Loads the track.
     *
     * Only the <em>structure</em> is validated here, by {@link Milestones#read}. Whether an item name, a
     * statistic or an advancement exists is not checked: that needs an initialised Bukkit registry, so the
     * plugin binds them at enable instead. Comparing the track to stored progress is {@code TrackValidation}'s
     * job and needs a database this method must not open.
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

    /**
     * Loads the sounds.
     *
     * <b>No validator.</b> Every rule about a sound is enforced in {@code FeedbackSounds}, and each corrects or
     * silences rather than refusing: a typo in a chime must not take a season offline.
     */
    public static ConfigHandle<SoundsSpec> sounds(final Path dataFolder, final Logger logger) throws ConfigException {
        return load(dataFolder, logger, "sounds", SoundsSpec.class, "NORDTAL_SMP_SOUNDS", config -> {}, false);
    }

    /**
     * Loads the tone colours.
     *
     * <b>No validator.</b> {@code ToneColours#parse} is where a bad hex value is caught, and it corrects rather than
     * refuses: a typo in a colour is not worth a season offline, the same rule {@link #sounds} follows for a bad sound
     * key.
     */
    public static ConfigHandle<ColoursSpec> colours(final Path dataFolder, final Logger logger) throws ConfigException {
        return load(dataFolder, logger, "colours", ColoursSpec.class, "NORDTAL_SMP_COLOURS", config -> {}, false);
    }

    /**
     * {@code ColoursSpec} 's five accessors, as the map {@link eu.nordtal.s2.common.message.ToneColours} parses.
     *
     * An exhaustive {@code switch} with no {@code default}, the same guard {@code SmpSounds} ' {@code specOf} uses for
     * {@code Feedback}: a sixth {@link Tone} stops this compiling until somebody says what its colour is called, rather
     * than silently leaving it unpainted.
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
     * Loads the crest ladder - hours and colours in one file.
     *
     * <b>The validator is only about the hours.</b> {@code PrestigeColours#parse} is where a bad hex value is caught,
     * and it corrects rather than refuses - the same rule {@link #colours} follows for the tone palette. A bad hour is
     * not that: {@link Prestige} 's constructor is the whole rule, and running it here is what makes a ladder that does
     * not rise stop the load rather than the first render.
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
     * The thirteen tier colours, in tier order.
     *
     * What {@link eu.nordtal.s2.smp.prestige.PrestigeColours#parse} takes.
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

    /**
     * The same thirteen keys of the other block, in the same order, as {@link Prestige} takes them.
     *
     * Two methods rather than one pair-returning method because the two halves are consumed by two different
     * objects at two different moments; what keeps them aligned is that both walk {@code tier01..tier13}, which
     * is the same contract the file's own header states.
     */
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
        // Whether the world exists is checked once at enable instead, after Worlds#bootstrap creates it.
        requireText("world-nordtal", config.worldNordtal());
        requireText("first-join-spawn: world", config.firstJoinSpawn().world());
        // AdminWatch floors this at one second, so a non-positive value silently becomes a query per second.
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
            // Zero is a real answer here - it means "never decays" - so it cannot double as the error case.
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

    /**
     * Writes {@code handle}'s {@link ConfigHandle#environmentOverrides()} next to its file.
     *
     * Lets steward-worker warn that editing an overridden setting there has no effect until the variable is
     * removed. Best-effort: this is a UI nicety, not a reason for a correctly loaded config to refuse to enable
     * the plugin.
     */
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
