package eu.nordtal.s2.smp.progress;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.DatabaseMessages;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.papercommon.time.PaperScheduler;
import eu.nordtal.s2.smp.announce.Announcer;
import eu.nordtal.s2.smp.aura.AuraDao;
import eu.nordtal.s2.smp.aura.AuraPayout;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneNames;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveProgress;
import eu.nordtal.s2.smp.milestone.ObjectiveRow;
import eu.nordtal.s2.smp.milestone.TrackDao;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.port.Contributions;
import eu.nordtal.s2.smp.port.OwnContributionRow;
import eu.nordtal.s2.smp.port.PrizeSource;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.world.Worlds;
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
 * The spine of the season: crediting progress, finishing objectives, paying out and unlocking milestones.
 *
 * Runs off the main thread. A credit with the finish and payout it causes is one transaction, joined by every DAO.
 */
public final class ObjectiveEngine implements Contributions {

    private final Plugin plugin;
    private final Jdbi jdbi;
    private final TrackDao rows;
    private final ProgressDao progress;
    private final AuraDao aura;
    /** The milestone track, as a supplier, because a settings change replaces it. */
    private final Supplier<MilestoneTrack> track;

    private final SeasonState season;
    private final Worlds worlds;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final PrizeSource prizes;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final Announcer announcer;

    /** How the milestone title sits on the screen: in fast, held long because it arrives unannounced, out slowly. */
    private static final Title.Times CEREMONY =
            Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1));

    /** What one transaction did: how much it credited, and the objective it finished, if it finished one. */
    private record Settled(long credited, @Nullable Finished finished) {

        static final Settled NOTHING = new Settled(0L, null);
    }

    /** An objective this engine finished and paid out, whose announcement waits for the commit. */
    private record Finished(String milestoneKey, String objectiveKey) {}

    /** @param jdbi the plugin's one Jdbi, whose transactions the {@link PrizeSource}'s writes join */
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

    @Override
    public List<OwnContributionRow> ownContributions(final String milestoneKey, final DiscordId discordId) {
        return progress.ownContributions(milestoneKey, discordId);
    }

    /**
     * Counts a holder of this advancement once towards the active milestone's gate naming it; blocking, so async.
     *
     * @param discordId who holds it
     * @param advancement the advancement held
     * @param completedBy the player who holds it; only changes the finishing sound
     * @return 1, or 0 when the gate names another advancement, is finished or counts this player already
     */
    public long creditAdvancement(
            final DiscordId discordId, final NamespacedKey advancement, final @Nullable UUID completedBy) {
        final Settled settled = jdbi.inTransaction(handle -> rows.activeMilestoneKey()
                .map(milestoneKey -> countUnder(milestoneKey, discordId, advancement))
                .orElse(Settled.NOTHING));
        afterCommit(settled, completedBy);
        return settled.credited();
    }

    /** Inside the caller's transaction: counts the holder once towards the gate, finishing it on the last one. */
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

    /** Inside the caller's transaction: adds what the objective still takes, finishing it when that fills it. */
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

    /**
     * Finishes one objective and pays its pot out, in one transaction, off the main thread.
     *
     * The admin escape hatch calls it too, which is why the completion guard is in SQL.
     */
    public void finishObjective(
            final String milestoneKey, final ObjectiveRow objective, final @Nullable UUID completedBy) {
        final Settled settled = jdbi.inTransaction(handle -> new Settled(0L, complete(milestoneKey, objective)));
        afterCommit(settled, completedBy);
    }

    /** Inside the caller's transaction: marks the objective finished and pays it out, or null when another did. */
    private @Nullable Finished complete(final String milestoneKey, final ObjectiveRow objective) {
        if (progress.completeObjective(objective.id()) == 0) {
            // Somebody else's delivery completed it a moment ago and has already paid everyone.
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
        final int pot = definition == null ? 0 : milestone.objectivePot();

        payOut(objective, pot, milestoneKey);
        return new Finished(milestoneKey, objective.key());
    }

    /**
     * After the commit: announces a finished objective and unlocks its milestone if it was the last.
     *
     * Logged, never thrown, because the credit it follows holds and a caller must not hand its items back.
     */
    private void afterCommit(final Settled settled, final @Nullable UUID completedBy) {
        final Finished finished = settled.finished();
        if (finished == null) {
            return;
        }
        try {
            announceObjective(finished.objectiveKey());
            // After the commit, so two objectives finished at once each see the other's completion.
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
     * Splits an objective's pot among everyone who qualified, by {@link AuraPayout}'s arithmetic.
     *
     * The pot is scaled down when the target was not reached, so the escape hatch never beats doing the work.
     */
    private void payOut(final ObjectiveRow objective, final int pot, final String milestoneKey) {
        if (pot <= 0) {
            return;
        }
        final List<ContributionRow> contributors = progress.contributionsOf(objective.id());
        if (contributors.isEmpty()) {
            return;
        }
        final Map<String, Long> contributions = new LinkedHashMap<>();
        for (final ContributionRow row : contributors) {
            contributions.put(row.discordId().value(), row.amount());
        }

        final int scaled = AuraPayout.scaledPot(pot, objective.amount(), objective.target());
        final List<AuraPayout.Share> shares = AuraPayout.split(scaled, objective.target(), contributions);
        final String ref = milestoneKey + "/" + objective.key();

        // In one order by player, so two payouts in flight lock the same rows in the same order and never deadlock.
        for (final AuraPayout.Share share : shares.stream()
                .sorted(Comparator.comparing(AuraPayout.Share::contributorId))
                .toList()) {
            if (share.total() <= 0) {
                continue;
            }
            aura.addAura(DiscordId.of(share.contributorId()), share.total(), AuraReason.CONTRIBUTION.stored(), ref);

            // The wheel's extra spins hang off the SAME share as the aura: one rule, one place to change it.
            final long contributed = contributions.getOrDefault(share.contributorId(), 0L);
            final double percent = objective.target() <= 0 ? 0.0 : (contributed * 100.0) / objective.target();
            final int spins = prizes.extraSpinsFor(percent);
            if (spins > 0) {
                prizes.grant(DiscordId.of(share.contributorId()), spins);
            }
        }
        plugin.getLogger()
                .info("objective " + ref + " paid " + shares.size() + " contributor(s) out of " + scaled + " aura");
    }

    /** Unlocks the milestone if every one of its objectives is now finished, off the main thread. */
    public void checkMilestone(final String milestoneKey, final @Nullable UUID completedBy) {
        final List<ObjectiveRow> objectives = rows.objectivesOf(milestoneKey);
        if (objectives.isEmpty() || !objectives.stream().allMatch(ObjectiveRow::completed)) {
            return;
        }
        unlockMilestone(milestoneKey, completedBy);
    }

    /**
     * Completes a milestone and activates the next in one transaction, then hands out what it unlocks, off main.
     *
     * The row and its {@code pg_notify} are one statement, so no announcement goes out for an unlock not held.
     */
    public void unlockMilestone(final String milestoneKey, final @Nullable UUID completedBy) {
        // One snapshot for the whole transition; separate reads of the supplier could disagree mid-reload.
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
                // Animated, unlike the one applied at start: the wall crawling outwards is the ceremony.
                worlds.expandNordtal(milestone.borderDiameter(), true);
            }
            announceMilestone(milestoneKey, completedBy, milestone == null ? Unlock.NOTHING : milestone.unlock());
        });
    }

    /** Announces a finished objective to everybody, without a sound, so the milestone's own sound keeps its weight. */
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
     * Tells everybody the milestone is finished, with a different sound for whoever finished it.
     *
     * An admin completion passes null, so nobody is congratulated for a command.
     */
    private void announceMilestone(final String milestoneKey, final @Nullable UUID completedBy, final Unlock unlock) {
        // Discord first, off this thread, one row per language.
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
                    renderer.format(locale, MESSAGES.smp().ceremony().title(name)),
                    renderer.format(locale, MESSAGES.smp().ceremony().subtitle()),
                    CEREMONY));
            sounds.play(
                    player, player.getUniqueId().equals(completedBy) ? Feedback.BIG_SUCCESS : Feedback.NETWORK_EVENT);
            effects.celebrate(player);
        }
    }
}
