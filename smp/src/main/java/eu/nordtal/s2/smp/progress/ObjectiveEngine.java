package eu.nordtal.s2.smp.progress;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.packrendering.Glyphs;
import eu.nordtal.s2.smp.SmpMessages;
import eu.nordtal.s2.smp.aura.AuraPayout;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.ContributionRow;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneNames;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveProgress;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.wheel.PrizeDraw;
import eu.nordtal.s2.smp.world.Worlds;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The spine of the season: crediting progress, finishing objectives, paying out and unlocking milestones.
 *
 * Runs off the main thread; a SQL guard, not a Java lock, makes each payout happen once.
 */
public final class ObjectiveEngine {

    private final Plugin plugin;
    private final SmpDao dao;
    /** The milestone track, as a supplier, because {@code /smp reload} replaces it. */
    private final java.util.function.Supplier<MilestoneTrack> track;

    private final SeasonState season;
    private final Worlds worlds;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSpec config;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final eu.nordtal.s2.smp.announce.Announcer announcer;

    /** How the milestone title sits on the screen: in fast, held long because it arrives unannounced, out slowly. */
    private static final Title.Times CEREMONY =
            Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1));

    public ObjectiveEngine(
            final Plugin plugin,
            final SmpDao dao,
            final java.util.function.Supplier<MilestoneTrack> track,
            final SeasonState season,
            final Worlds worlds,
            final Identities identities,
            final Messages messages,
            final PlayerLocales locales,
            final SmpSpec config,
            final SmpSounds sounds,
            final WorldEffects effects,
            final eu.nordtal.s2.smp.announce.Announcer announcer) {
        this.announcer = java.util.Objects.requireNonNull(announcer, "announcer");
        this.plugin = plugin;
        this.dao = dao;
        this.track = track;
        this.season = season;
        this.worlds = worlds;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.config = config;
        this.sounds = sounds;
        this.effects = effects;
    }

    /**
     * Credits {@code delta} towards an objective of the active milestone; blocking, so call it from an async task.
     *
     * @param discordId who to credit
     * @param objectiveKey which objective of the active milestone
     * @param delta how much, in the objective's own unit
     * @param completedBy the player the credit came from, or null for an admin; only changes the finishing sound
     * @return {@code delta}, or 0 when it is not positive or the active milestone has no such open objective
     */
    public long credit(
            final DiscordId discordId, final String objectiveKey, final long delta, final @Nullable UUID completedBy) {
        if (delta <= 0) {
            return 0L;
        }
        final Optional<String> activeKey = dao.activeMilestoneKey();
        if (activeKey.isEmpty()) {
            return 0L;
        }
        return creditUnder(activeKey.get(), discordId, objectiveKey, delta, completedBy);
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
        final Optional<String> activeKey = dao.activeMilestoneKey();
        if (activeKey.isEmpty()) {
            return 0L;
        }
        final Optional<ObjectiveRow> gate = track.get()
                .milestone(activeKey.get())
                .flatMap(milestone -> milestone.gateFor(advancement))
                .flatMap(objective -> dao.objective(activeKey.get(), objective.key()));
        if (gate.isEmpty() || gate.get().completed()) {
            return 0L;
        }
        final ObjectiveRow before = gate.get();
        final Optional<Long> amount = dao.countOnce(before.id(), discordId);
        if (amount.isEmpty()) {
            return 0L;
        }
        if (ObjectiveProgress.advance(amount.get() - 1L, before.target(), 1L).completes()) {
            finishObjective(activeKey.get(), before.withAmount(amount.get()), completedBy);
        }
        return 1L;
    }

    private long creditUnder(
            final String milestoneKey,
            final DiscordId discordId,
            final String objectiveKey,
            final long delta,
            final @Nullable UUID completedBy) {
        final Optional<ObjectiveRow> row = dao.objective(milestoneKey, objectiveKey);
        if (row.isEmpty() || row.get().completed()) {
            return 0L;
        }

        final ObjectiveRow objective = row.get();
        final ObjectiveProgress.Advance advance =
                ObjectiveProgress.advance(objective.amount(), objective.target(), delta);
        if (advance.credited() <= 0) {
            return 0L;
        }

        dao.addObjectiveProgress(objective.id(), advance.credited());
        dao.addContribution(objective.id(), discordId, advance.credited());

        if (advance.completes()) {
            finishObjective(milestoneKey, objective.withAmount(advance.amount()), completedBy);
        }
        return advance.credited();
    }

    /**
     * Finishes one objective and pays its pot out, off the main thread.
     *
     * The admin escape hatch calls it too, which is why the completion guard is in SQL.
     */
    public void finishObjective(
            final String milestoneKey, final ObjectiveRow objective, final @Nullable UUID completedBy) {
        if (dao.completeObjective(objective.id()) == 0) {
            // Somebody else's delivery completed it a moment ago and has already paid everyone.
            return;
        }

        final Milestone milestone = track.get().milestone(milestoneKey).orElse(null);
        if (milestone == null) {
            plugin.getLogger()
                    .warning("objective '" + objective.key() + "' completed under milestone '" + milestoneKey
                            + "', which the track no longer declares - no aura was paid");
            return;
        }
        final Objective definition = milestone.objective(objective.key()).orElse(null);
        final int pot = definition == null ? 0 : milestone.objectivePot();

        payOut(objective, pot, milestoneKey);
        announceObjective(objective.key());
        checkMilestone(milestoneKey, completedBy);
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
        final List<ContributionRow> rows = dao.contributionsOf(objective.id());
        if (rows.isEmpty()) {
            return;
        }
        final Map<String, Long> contributions = new LinkedHashMap<>();
        for (final ContributionRow row : rows) {
            contributions.put(row.discordId().value(), row.amount());
        }

        final int scaled = AuraPayout.scaledPot(pot, objective.amount(), objective.target());
        final List<AuraPayout.Share> shares = AuraPayout.split(scaled, objective.target(), contributions);
        final String ref = milestoneKey + "/" + objective.key();

        for (final AuraPayout.Share share : shares) {
            if (share.total() <= 0) {
                continue;
            }
            dao.addAura(DiscordId.of(share.contributorId()), share.total(), AuraReason.CONTRIBUTION.stored(), ref);

            // The wheel's extra spins hang off the SAME thresholds as the aura share: one rule, one place to change it.
            final long contributed = contributions.getOrDefault(share.contributorId(), 0L);
            final double percent = objective.target() <= 0 ? 0.0 : (contributed * 100.0) / objective.target();
            final int spins = PrizeDraw.extraSpinsFor(config.wheelExtraSpinPercents(), percent);
            if (spins > 0) {
                dao.grantSpins(DiscordId.of(share.contributorId()), spins);
            }
        }
        plugin.getLogger()
                .info("objective " + ref + " paid " + shares.size() + " contributor(s) out of " + scaled + " aura");
    }

    /** Unlocks the milestone if every one of its objectives is now finished, off the main thread. */
    public void checkMilestone(final String milestoneKey, final @Nullable UUID completedBy) {
        final List<ObjectiveRow> objectives = dao.objectivesOf(milestoneKey);
        if (objectives.isEmpty() || !objectives.stream().allMatch(ObjectiveRow::completed)) {
            return;
        }
        unlockMilestone(milestoneKey, completedBy);
    }

    /**
     * Completes a milestone, hands out what it unlocks and activates the next, off the main thread.
     *
     * The row and its {@code pg_notify} are one statement, so no announcement goes out for an unlock not held.
     */
    public void unlockMilestone(final String milestoneKey, final @Nullable UUID completedBy) {
        if (dao.completeMilestone(milestoneKey).isEmpty()) {
            return;
        }

        // One snapshot for the whole transition; separate reads of the supplier could disagree mid-reload.
        final MilestoneTrack now = track.get();
        now.after(milestoneKey).ifPresent(next -> dao.activateMilestone(next.key()));
        season.refresh(dao.completedMilestoneKeys(), now);

        final Milestone milestone = now.milestone(milestoneKey).orElse(null);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (milestone != null && milestone.unlock() == Unlock.BORDER) {
                // Animated, unlike the one applied at start: the wall crawling outwards is the ceremony.
                worlds.expandNordtal(milestone.borderDiameter(), true);
            }
            announceMilestone(milestoneKey, completedBy, milestone == null ? Unlock.NOTHING : milestone.unlock());
        });
    }

    /** Announces a finished objective to everybody, without a sound, so the milestone's own sound keeps its weight. */
    private void announceObjective(final String objectiveKey) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                final var locale = locales.of(player.getUniqueId());
                player.sendMessage(MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().objective().completed(Glyphs.ICON_ANNOUNCE, objectiveKey)));
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
            final MilestoneContext milestone = new MilestoneContext(MilestoneNames.of(messages, locale, milestoneKey));
            final SmpMessages.Smp.Announce.Milestone by =
                    MESSAGES.smp().announce().milestoneSection();
            return switch (unlock) {
                case BORDER -> by.border(milestone);
                case NETHER -> by.nether(milestone);
                case END -> by.end(milestone);
                case NOTHING -> MESSAGES.smp().announce().milestone(milestone);
            };
        });
        final MessageRenderer renderer = MessageRenderer.of(messages);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final var locale = locales.of(player.getUniqueId());
            final MilestoneContext name = new MilestoneContext(MilestoneNames.of(messages, locale, milestoneKey));

            player.sendMessage(
                    renderer.format(locale, MESSAGES.smp().milestone().completed(Glyphs.ICON_ANNOUNCE, name)));
            player.showTitle(Title.title(
                    renderer.format(locale, MESSAGES.smp().ceremony().title(name)),
                    renderer.format(locale, MESSAGES.smp().ceremony().subtitle()),
                    CEREMONY));
            sounds.play(
                    player, player.getUniqueId().equals(completedBy) ? Feedback.BIG_SUCCESS : Feedback.NETWORK_EVENT);
            effects.celebrate(player);
        }
    }

    /** Which Discord account a player's contributions belong to, or empty if they are not linked. */
    public Optional<DiscordId> discordIdOf(final Player player) {
        return identities.discordIdOf(player.getUniqueId());
    }
}
