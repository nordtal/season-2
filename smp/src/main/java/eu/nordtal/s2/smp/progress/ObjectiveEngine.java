package eu.nordtal.s2.smp.progress;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.context.MilestoneContext;
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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The spine of the season: crediting progress, finishing objectives, paying out and unlocking milestones.
 *
 * That includes the milestone waiting on a finished objective. Everything here runs off the main thread; only the
 * world border, the announcements and the surfaces hop back.
 *
 * Two players can finish the same objective in the same instant. What makes the payout happen once is a guard in
 * SQL, not a lock in Java: {@code UPDATE ... WHERE completed IS NULL} changes a row for exactly one of them. The
 * same shape guards the milestone itself.
 */
public final class ObjectiveEngine {

    private final Plugin plugin;
    private final SmpDao dao;
    /**
     * The milestone track, <b>as a supplier</b>.
     *
     * {@code /smp reload} replaces the plugin's track with a new instance, so a reference captured at enable would
     * silently go on reading the definitions the server started with.
     */
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

    /**
     * How the milestone title sits on the screen: in fast, held long, out slowly.
     *
     * Held long because it arrives unannounced - nobody pressed anything.
     */
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
     * Credits {@code delta} towards an objective of the active milestone. <b>Blocking - call from an async task.</b>
     *
     * Only the active milestone accepts progress. Contributing to a locked one would mean the track could be
     * finished out of order, and contributing to a completed one would mean a payout that has already happened
     * being recalculated.
     *
     * @param discordId who to credit
     * @param objectiveKey which objective of the active milestone
     * @param delta how much, in the objective's own unit
     * @param completedBy the player this credit came from, or null for an admin's escape hatch; carried only so a
     *     milestone finished by this credit sounds different to the person who finished it
     * @return how much was actually credited, which is less than {@code delta} when the objective was finished by it
     */
    public long credit(
            final String discordId, final String objectiveKey, final long delta, final @Nullable UUID completedBy) {
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
     * Called both by {@link #credit} and by the admin escape hatch, which is why the completion guard lives in SQL
     * rather than in the caller.
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
     * Splits an objective's pot among everyone who qualified.
     *
     * The arithmetic is {@link AuraPayout} 's and is tested there: 30 % split equally among qualifiers, 70 % in
     * proportion, a 2 % qualifying threshold and a one-aura minimum share. What is here is only the reading and the
     * writing.
     *
     * <b>The pot is scaled when the objective did not actually reach its target</b> - an admin completion, or a target
     * lowered below the collected amount - so the escape hatch is never worth more than doing the work.
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

            // The wheel's extra spins hang off the SAME thresholds as the aura share: one rule, one place to change it.
            final long contributed = contributions.getOrDefault(share.contributorId(), 0L);
            final double percent = objective.target() <= 0 ? 0.0 : (contributed * 100.0) / objective.target();
            final int spins = PrizeDraw.extraSpinsFor(config.wheelExtraSpinPercents(), percent);
            if (spins > 0) {
                dao.grantSpins(share.contributorId(), spins);
            }
        }
        plugin.getLogger()
                .info("objective " + ref + " paid " + shares.size() + " contributor(s) out of " + scaled + " aura");
    }

    /**
     * Unlocks the milestone if every one of its objectives is now finished. <b>Async.</b>
     */
    public void checkMilestone(final String milestoneKey, final @Nullable UUID completedBy) {
        final List<ObjectiveRow> objectives = dao.objectivesOf(milestoneKey);
        if (objectives.isEmpty() || !objectives.stream().allMatch(ObjectiveRow::completed)) {
            return;
        }
        unlockMilestone(milestoneKey, completedBy);
    }

    /**
     * Completes a milestone, hands out what it unlocks, and activates the next. <b>Async.</b>
     *
     * The row and the {@code pg_notify} that tells Discord are one statement, so an announcement can never go out for
     * an
     * unlock the database does not hold.
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

    /**
     * One objective of the active milestone is finished, said to everybody - and said silently.
     *
     * The silence is the middle rung of a ladder: a hand-in is {@code SMALL_SUCCESS} for the one player, a milestone is
     * {@code BIG_SUCCESS} and {@code NETWORK_EVENT}. There are several objectives per milestone, so a network-wide
     * sound
     * here would devalue the milestone's own.
     */
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
     * Tells everybody the milestone is finished - and tells the person who finished it differently.
     *
     * Same line, two sounds: {@code BIG_SUCCESS} for whoever's contribution closed the last objective and
     * {@code NETWORK_EVENT} for the rest of the server. An admin's hand-completion passes null, so everybody hears the
     * network event and nobody is congratulated for a command.
     *
     * The rockets go up around every player, wherever they are standing, because the season has no one place everybody
     * is. Nothing here is scheduled or staggered: a milestone closes a handful of times a season and the whole ceremony
     * is one tick's work per online player.
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
    public Optional<String> discordIdOf(final Player player) {
        return identities.discordIdOf(player.getUniqueId());
    }
}
