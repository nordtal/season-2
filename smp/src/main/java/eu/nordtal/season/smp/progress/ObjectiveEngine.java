package eu.nordtal.season.smp.progress;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.announce.Announcer;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.feedback.WorldEffects;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveProgress;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.Payout;
import eu.nordtal.season.smp.milestone.TrackDao;
import eu.nordtal.season.smp.milestone.Unlock;
import eu.nordtal.season.smp.port.Contributions;
import eu.nordtal.season.smp.port.OwnContributionRow;
import eu.nordtal.season.smp.port.PrizeSource;
import eu.nordtal.season.smp.state.SeasonState;
import eu.nordtal.season.smp.world.Worlds;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * Credits objectives, completes them, pays their aura and spins and unlocks milestones.
 * Its methods run off the main thread.
 * A credit, the completion it causes and the payout run in one transaction.
 */
public final class ObjectiveEngine implements Contributions {

    private final Plugin plugin;
    private final Jdbi jdbi;
    private final TrackDao rows;
    private final ProgressDao progress;
    private final AuraDao aura;
    private final Supplier<MilestoneTrack> track;

    private final SeasonState season;
    private final Worlds worlds;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final PrizeSource prizes;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final Announcer announcer;

    /** The fade in, stay and fade out of the milestone title. */
    private static final Title.Times CEREMONY =
            Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1));

    /** The result of one transaction: the amount credited and the objective it completed, if any. */
    private record Settled(long credited, @Nullable Finished finished) {

        static final Settled NOTHING = new Settled(0L, null);
    }

    /** An objective completed in a transaction, announced after the commit. */
    private record Finished(String milestoneKey, String objectiveKey) {}

    /** @param jdbi the {@code Jdbi} whose transactions the writes of {@link PrizeSource} join */
    public ObjectiveEngine(
            final Plugin plugin,
            final Jdbi jdbi,
            final Supplier<MilestoneTrack> track,
            final SeasonState season,
            final Worlds worlds,
            final Identities identities,
            final MessageRenderer renderer,
            final PrizeSource prizes,
            final SmpSounds sounds,
            final WorldEffects effects,
            final Announcer announcer) {
        this.announcer = java.util.Objects.requireNonNull(announcer, "announcer");
        this.plugin = plugin;
        this.jdbi = jdbi;
        this.rows = jdbi.onDemand(TrackDao.class);
        this.progress = jdbi.onDemand(ProgressDao.class);
        this.aura = jdbi.onDemand(AuraDao.class);
        this.track = track;
        this.season = season;
        this.worlds = worlds;
        this.identities = identities;
        this.renderer = renderer;
        this.prizes = prizes;
        this.sounds = sounds;
        this.effects = effects;
    }

    @Override
    public long credit(
            final DiscordId discordId, final String objectiveKey, final long delta, final @Nullable UUID completedBy) {
        if (delta <= 0) {
            return 0L;
        }
        final Settled settled = jdbi.inTransaction(handle -> rows.activeMilestoneKey()
                .map(milestoneKey -> creditUnder(milestoneKey, discordId, objectiveKey, delta))
                .orElse(Settled.NOTHING));
        afterCommit(settled, completedBy);
        return settled.credited();
    }

    /** Computes the spin share of each row with {@link Payout#shareOf}: what completing the objective now pays. */
    @Override
    public List<OwnContributionRow> ownContributions(final String milestoneKey, final DiscordId discordId) {
        final Optional<Milestone> milestone = track.get().milestone(milestoneKey);
        final Map<String, Map<String, Long>> everybody = new LinkedHashMap<>();
        for (final KeyedContributionRow row : progress.contributionsUnder(milestoneKey)) {
            everybody
                    .computeIfAbsent(row.key(), key -> new LinkedHashMap<>())
                    .put(row.discordId().value(), row.amount());
        }
        return progress.ownContributions(milestoneKey, discordId).stream()
                .map(row -> {
                    final int budget = milestone
                            .flatMap(found -> found.objective(row.key()))
                            .map(Objective::spinBudget)
                            .orElse(0);
                    final Map<String, Long> contributions = everybody.getOrDefault(row.key(), Map.of());
                    return new OwnContributionRow(
                            row.key(),
                            row.mine(),
                            row.target(),
                            Payout.shareOf(budget, row.reached(), row.target(), contributions, discordId.value()),
                            budget);
                })
                .toList();
    }

    /**
     * Adds 1 to the participation gate of the active milestone that names {@code advancement}.
     * It blocks on the database.
     *
     * @param completedBy the player who holds the advancement, which selects the sound of a milestone unlock
     * @return 1, or 0 when no such gate exists, the gate is completed or already counts {@code discordId}
     */
    public long creditAdvancement(
            final DiscordId discordId, final NamespacedKey advancement, final @Nullable UUID completedBy) {
        final Settled settled = jdbi.inTransaction(handle -> rows.activeMilestoneKey()
                .map(milestoneKey -> countUnder(milestoneKey, discordId, advancement))
                .orElse(Settled.NOTHING));
        afterCommit(settled, completedBy);
        return settled.credited();
    }

    /** Adds 1 to the gate in the transaction of the caller and completes the gate when it reaches its target. */
    private Settled countUnder(final String milestoneKey, final DiscordId discordId, final NamespacedKey advancement) {
        final Optional<ObjectiveRow> gate = track.get()
                .milestone(milestoneKey)
                .flatMap(milestone -> milestone.gateFor(advancement))
                .flatMap(objective -> rows.objective(milestoneKey, objective.key()));
        if (gate.isEmpty() || gate.get().completed()) {
            return Settled.NOTHING;
        }
        final ObjectiveRow before = gate.get();
        final Optional<Long> amount = progress.countOnce(before.id(), discordId);
        if (amount.isEmpty()) {
            return Settled.NOTHING;
        }
        if (ObjectiveProgress.advance(amount.get() - 1L, before.target(), 1L).completes()) {
            return new Settled(1L, complete(milestoneKey, before.withAmount(amount.get())));
        }
        return new Settled(1L, null);
    }

    /** Adds {@code delta} to the objective and completes it at its target, in the transaction of the caller. */
    private Settled creditUnder(
            final String milestoneKey, final DiscordId discordId, final String objectiveKey, final long delta) {
        final Optional<ObjectiveRow> row = rows.objective(milestoneKey, objectiveKey);
        if (row.isEmpty() || row.get().completed()) {
            return Settled.NOTHING;
        }

        final ObjectiveRow objective = row.get();
        final ObjectiveProgress.Advance advance =
                ObjectiveProgress.advance(objective.amount(), objective.target(), delta);
        if (advance.credited() <= 0) {
            return Settled.NOTHING;
        }

        progress.addObjectiveProgress(objective.id(), advance.credited());
        progress.addContribution(objective.id(), discordId, advance.credited());

        if (advance.completes()) {
            return new Settled(advance.credited(), complete(milestoneKey, objective.withAmount(advance.amount())));
        }
        return new Settled(advance.credited(), null);
    }

    /** Completes the objective and pays its budgets in one transaction. */
    public void finishObjective(
            final String milestoneKey, final ObjectiveRow objective, final @Nullable UUID completedBy) {
        final Settled settled = jdbi.inTransaction(handle -> new Settled(0L, complete(milestoneKey, objective)));
        afterCommit(settled, completedBy);
    }

    /** Completes and pays the objective in the transaction of the caller, or returns null if it was completed. */
    private @Nullable Finished complete(final String milestoneKey, final ObjectiveRow objective) {
        if (progress.completeObjective(objective.id()) == 0) {
            return null;
        }

        final Milestone milestone = track.get().milestone(milestoneKey).orElse(null);
        if (milestone == null) {
            plugin.getLogger()
                    .warning("objective '" + objective.key() + "' completed under milestone '" + milestoneKey
                            + "', which the track no longer declares - no aura was paid");
            return null;
        }
        final Objective definition = milestone.objective(objective.key()).orElse(null);
        if (definition != null) {
            payOut(objective, definition, milestoneKey);
        }
        return new Finished(milestoneKey, objective.key());
    }

    /**
     * Announces the completed objective and checks its milestone.
     * It logs a {@code RuntimeException} and does not rethrow it.
     */
    private void afterCommit(final Settled settled, final @Nullable UUID completedBy) {
        final Finished finished = settled.finished();
        if (finished == null) {
            return;
        }
        try {
            announceObjective(finished.objectiveKey());
            // Runs after the commit so that two objectives completed at once see each other.
            checkMilestone(finished.milestoneKey(), completedBy);
        } catch (final RuntimeException failure) {
            plugin.getLogger()
                    .log(
                            Level.SEVERE,
                            "objective " + finished.milestoneKey() + "/" + finished.objectiveKey()
                                    + " is finished and paid, but its milestone was not checked; unlock it by hand",
                            failure);
        }
    }

    /**
     * Pays the aura and spin budgets of the objective to its contributors with {@link Payout#paid}.
     */
    private void payOut(final ObjectiveRow objective, final Objective definition, final String milestoneKey) {
        final List<ContributionRow> contributors = progress.contributionsOf(objective.id());
        if (contributors.isEmpty()) {
            return;
        }
        final Map<String, Long> contributions = new LinkedHashMap<>();
        for (final ContributionRow row : contributors) {
            contributions.put(row.discordId().value(), row.amount());
        }

        final List<Payout.Share> auraShares =
                byPlayer(Payout.paid(definition.auraBudget(), objective.amount(), objective.target(), contributions));
        final List<Payout.Share> spinShares =
                byPlayer(Payout.paid(definition.spinBudget(), objective.amount(), objective.target(), contributions));

        // Paying in the order of the player id makes concurrent payouts lock rows in the same order.
        for (final Payout.Share share : auraShares) {
            aura.addAura(
                    DiscordId.of(share.contributorId()), share.total(), new AuraCause.Contribution(objective.id()));
        }
        for (final Payout.Share share : spinShares) {
            prizes.grant(DiscordId.of(share.contributorId()), share.total());
        }
        plugin.getLogger()
                .info("objective " + milestoneKey + "/" + objective.key() + " paid " + contributions.size()
                        + " contributor(s) " + sum(auraShares) + " aura and " + sum(spinShares) + " spins");
    }

    private static int sum(final List<Payout.Share> shares) {
        return shares.stream().mapToInt(Payout.Share::total).sum();
    }

    /** Returns the shares with a positive total, sorted by player id. */
    private static List<Payout.Share> byPlayer(final List<Payout.Share> shares) {
        return shares.stream()
                .filter(share -> share.total() > 0)
                .sorted(Comparator.comparing(Payout.Share::contributorId))
                .toList();
    }

    /** Unlocks the milestone when it has objectives and all of them are completed. */
    public void checkMilestone(final String milestoneKey, final @Nullable UUID completedBy) {
        final List<ObjectiveRow> objectives = rows.objectivesOf(milestoneKey);
        if (objectives.isEmpty() || !objectives.stream().allMatch(ObjectiveRow::completed)) {
            return;
        }
        unlockMilestone(milestoneKey, completedBy);
    }

    /**
     * Completes the milestone, activates the next one and applies the unlock of the milestone.
     * It does nothing when the milestone is not {@code ACTIVE}.
     */
    public void unlockMilestone(final String milestoneKey, final @Nullable UUID completedBy) {
        // One read of the supplier, because a reload can replace the track between two reads.
        final MilestoneTrack now = track.get();
        final boolean unlocked = jdbi.inTransaction(handle -> {
            if (rows.completeMilestone(milestoneKey).isEmpty()) {
                return false;
            }
            now.after(milestoneKey).ifPresent(next -> rows.activateMilestone(next.key()));
            return true;
        });
        if (!unlocked) {
            return;
        }
        season.refresh(rows.completedMilestoneKeys(), now);

        final Milestone milestone = now.milestone(milestoneKey).orElse(null);
        PaperScheduler.of(plugin).onMain(() -> {
            if (milestone != null && milestone.unlock() == Unlock.BORDER) {
                worlds.expandNordtal(milestone.borderDiameter(), true);
            }
            announceMilestone(milestoneKey, completedBy, milestone == null ? Unlock.NOTHING : milestone.unlock());
        });
    }

    /** Sends the completion of the objective to every online player, without a sound. */
    private void announceObjective(final String objectiveKey) {
        PaperScheduler.of(plugin).onMain(() -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                final var locale = identities.languageOf(player.getUniqueId());
                player.sendMessage(
                        renderer.format(locale, MESSAGES.smp().objective().completed(objectiveKey)));
            }
        });
    }

    /**
     * Announces the unlocked milestone on Discord and to every online player.
     * The player {@code completedBy} hears {@code BIG_SUCCESS} and everybody else {@code NETWORK_EVENT}.
     */
    private void announceMilestone(final String milestoneKey, final @Nullable UUID completedBy, final Unlock unlock) {
        announcer.announce(locale -> {
            final MilestoneContext milestone =
                    new MilestoneContext(MilestoneNames.of(renderer.raw(), locale, milestoneKey));
            final DatabaseMessages.Announcements announcement = DatabaseMessages.MESSAGES.announcement();
            return switch (unlock) {
                case BORDER -> announcement.milestoneSection().border(milestone);
                case NETHER -> announcement.milestoneSection().nether(milestone);
                case END -> announcement.milestoneSection().end(milestone);
                case NOTHING -> announcement.milestone(milestone);
            };
        });
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final var locale = identities.languageOf(player.getUniqueId());
            final MilestoneContext name = new MilestoneContext(MilestoneNames.of(renderer.raw(), locale, milestoneKey));

            player.sendMessage(
                    renderer.format(locale, MESSAGES.smp().milestone().completed(name)));
            player.showTitle(Title.title(
                    Lines.joined(
                            renderer.format(locale, MESSAGES.smp().ceremony().title(name))),
                    Lines.joined(
                            renderer.format(locale, MESSAGES.smp().ceremony().subtitle())),
                    CEREMONY));
            sounds.play(
                    player, player.getUniqueId().equals(completedBy) ? Feedback.BIG_SUCCESS : Feedback.NETWORK_EVENT);
            effects.celebrate(player);
        }
    }
}
