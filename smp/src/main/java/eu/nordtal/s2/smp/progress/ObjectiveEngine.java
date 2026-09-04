package eu.nordtal.s2.smp.progress;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.aura.AuraPayout;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.db.ContributionRow;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveProgress;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.wheel.PrizeDraw;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The spine of the season: crediting progress, finishing an objective, paying it out, and unlocking
 * the milestone that was waiting on it.
 *
 * <h2>Everything here runs off the main thread</h2>
 * Every method that touches the database says so, and the only things that hop back are the world
 * border, the announcements and the surfaces. A milestone unlock is the moment the whole server is
 * watching, which makes it the worst possible moment to be holding the server thread on a query.
 *
 * <h2>Completing exactly once</h2>
 * Two players can finish the same objective in the same instant. What makes the payout happen once
 * is not a lock in Java but a guard in SQL: {@code UPDATE ... WHERE completed IS NULL} changes a row
 * for exactly one of them, and only the caller whose update changed something goes on to pay
 * anybody. The same shape guards the milestone itself.
 */
public final class ObjectiveEngine {

    private final Plugin plugin;
    private final SmpDao dao;
    private final MilestoneTrack track;
    private final SeasonState season;
    private final Worlds worlds;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSpec config;
    private final SmpSounds sounds;

    public ObjectiveEngine(final Plugin plugin, final SmpDao dao, final MilestoneTrack track,
                           final SeasonState season, final Worlds worlds, final Identities identities,
                           final Messages messages, final PlayerLocales locales, final SmpSpec config,
                           final SmpSounds sounds) {
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
    }

    /**
     * Credits {@code delta} towards an objective of the active milestone. <b>Blocking - call from an
     * async task.</b>
     *
     * <p>Only the active milestone accepts progress. Contributing to a locked one would mean the
     * track could be finished out of order, and contributing to a completed one would mean a payout
     * that has already happened being recalculated.
     *
     * @param discordId  who to credit
     * @param objectiveKey which objective of the active milestone
     * @param delta      how much, in the objective's own unit
     * @param completedBy the player this credit came from, or null when nobody is standing behind
     *                    it - an admin's escape hatch. Carried through only so that a milestone
     *                    finished by this credit sounds different to the person who finished it
     *                    than it does to everybody else
     * @return how much was actually credited, which is less than {@code delta} when the objective
     *         was finished by it
     */
    public long credit(final String discordId, final String objectiveKey, final long delta,
                       final UUID completedBy) {
        if (delta <= 0) {
            return 0L;
        }
        final Optional<String> activeKey = dao.activeMilestoneKey();
        if (activeKey.isEmpty()) {
            return 0L;
        }
        final Optional<ObjectiveRow> row = dao.objective(activeKey.get(), objectiveKey);
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
            finishObjective(activeKey.get(), objective, completedBy);
        }
        return advance.credited();
    }

    /**
     * Finishes one objective and pays its pot out. <b>Async.</b>
     *
     * <p>Called both by {@link #credit} and by the admin escape hatch, which is why the completion
     * guard lives in SQL rather than in the caller.
     */
    public void finishObjective(final String milestoneKey, final ObjectiveRow objective,
                                final UUID completedBy) {
        if (dao.completeObjective(objective.id()) == 0) {
            // Somebody else's delivery completed it a moment ago and has already paid everyone.
            return;
        }

        final Milestone milestone = track.milestone(milestoneKey).orElse(null);
        if (milestone == null) {
            plugin.getLogger().warning("objective '" + objective.key() + "' completed under milestone '"
                    + milestoneKey + "', which the track no longer declares - no aura was paid");
            return;
        }
        final Objective definition = milestone.objective(objective.key()).orElse(null);
        final int pot = definition == null ? 0 : milestone.objectivePot();

        payOut(objective, pot, milestoneKey);
        announceObjective(milestoneKey, objective.key());
        checkMilestone(milestoneKey, completedBy);
    }

    /**
     * Splits an objective's pot among everyone who qualified.
     *
     * <p>The arithmetic is {@link AuraPayout}'s and is tested there: 30 % split equally among
     * qualifiers, 70 % in proportion, a 2 % qualifying threshold and a one-aura minimum share. What
     * is here is only the reading and the writing.
     *
     * <p><b>The pot is scaled when the objective did not actually reach its target</b>, which
     * happens when an admin completes one by hand or lowers a target below the collected amount.
     * Paying the full pot for a partial objective would make the escape hatch worth more than doing
     * the work.
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
            contributions.put(row.discordId(), row.amount());
        }

        final int scaled = AuraPayout.scaledPot(pot, objective.amount(), objective.target());
        final List<AuraPayout.Share> shares = AuraPayout.split(scaled, objective.target(), contributions);
        final String ref = milestoneKey + "/" + objective.key();

        for (final AuraPayout.Share share : shares) {
            if (share.total() <= 0) {
                continue;
            }
            dao.addAura(share.contributorId(), share.total(), AuraReason.CONTRIBUTION.stored(), ref);

            // The wheel's extra spins hang off the SAME thresholds as the aura share, on purpose:
            // one rule to understand, one place to change it. That the biggest contributors collect
            // both is accepted - it is the only place in this design where effort compounds, and it
            // compounds into loot rather than into rank.
            final long contributed = contributions.getOrDefault(share.contributorId(), 0L);
            final double percent = objective.target() <= 0 ? 0.0
                    : (contributed * 100.0) / objective.target();
            final int spins = PrizeDraw.extraSpinsFor(config.wheelExtraSpinPercents(), percent);
            if (spins > 0) {
                dao.grantSpins(share.contributorId(), spins);
            }
        }
        plugin.getLogger().info("objective " + ref + " paid " + shares.size() + " contributor(s) out of "
                + scaled + " aura");
    }

    /**
     * Unlocks the milestone if every one of its objectives is now finished. <b>Async.</b>
     */
    public void checkMilestone(final String milestoneKey, final UUID completedBy) {
        final List<ObjectiveRow> objectives = dao.objectivesOf(milestoneKey);
        if (objectives.isEmpty() || !objectives.stream().allMatch(ObjectiveRow::completed)) {
            return;
        }
        unlockMilestone(milestoneKey, completedBy);
    }

    /**
     * Completes a milestone, hands out what it unlocks, and activates the next. <b>Async.</b>
     *
     * <p>The row and the {@code pg_notify} that tells Discord are one statement, so an announcement
     * can never go out for an unlock the database does not hold.
     */
    public void unlockMilestone(final String milestoneKey, final UUID completedBy) {
        if (dao.completeMilestone(milestoneKey).isEmpty()) {
            return;
        }

        track.after(milestoneKey).ifPresent(next -> dao.activateMilestone(next.key()));
        season.refresh(dao.completedMilestoneKeys(), track);

        final Milestone milestone = track.milestone(milestoneKey).orElse(null);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (milestone != null && milestone.unlock() == Unlock.BORDER) {
                // Animated, unlike the one applied at start: this one is happening now, and the wall
                // crawling outwards is the ceremony.
                worlds.expandNordtal(milestone.borderDiameter(), true);
            }
            announceMilestone(milestoneKey, completedBy);
        });
    }

    // ------------------------------------------------------------------ announcements

    private void announceObjective(final String milestoneKey, final String objectiveKey) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                final var locale = locales.of(player.getUniqueId());
                player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.objective.completed",
                        "objective", nameOf("smp.objective." + milestoneKey + "." + objectiveKey,
                                objectiveKey, locale)));
            }
        });
    }

    /**
     * Tells everybody the milestone is finished - and tells the person who finished it differently.
     *
     * <p>Same line, two sounds: {@code BIG_SUCCESS} for whoever's contribution closed the last
     * objective and {@code NETWORK_EVENT} for the rest of the server. An admin's hand-completion
     * passes null, so everybody hears the network event and nobody is congratulated for a command.
     */
    private void announceMilestone(final String milestoneKey, final UUID completedBy) {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final var locale = locales.of(player.getUniqueId());
            player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.milestone.completed",
                    "milestone", nameOf("smp.milestone." + milestoneKey, milestoneKey, locale)));
            sounds.play(player, player.getUniqueId().equals(completedBy)
                    ? Feedback.BIG_SUCCESS : Feedback.NETWORK_EVENT);
        }
    }

    private String nameOf(final String key, final String fallback, final java.util.Locale locale) {
        return messages.hasTranslation(locale, key) ? messages.get(locale, key) : fallback;
    }

    /** Which Discord account a player's contributions belong to, or empty if they are not linked. */
    public Optional<String> discordIdOf(final Player player) {
        return identities.discordIdOf(player.getUniqueId());
    }
}
