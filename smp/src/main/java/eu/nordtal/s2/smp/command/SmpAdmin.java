package eu.nordtal.s2.smp.command;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.access.AccessState;
import eu.nordtal.s2.database.access.OpenPayment;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.database.phase.SeasonDates;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.context.DiscordMemberContext;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.papercommon.command.Answer;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.player.Identities;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The SMP's admin actions, the same whether the console typed them or Steward asked through the inbox.
 *
 * Every method reads the database, so none runs on the main thread.
 */
public final class SmpAdmin {

    /** What closing an objective or unlocking a milestone by hand does to the running track. */
    public interface Track {

        /** Closes {@code objective} of {@code milestone} and pays out what was collected, with nobody behind it. */
        void finishObjective(String milestone, ObjectiveRow objective);

        /** Unlocks {@code milestone} and pays everybody who qualified, with nobody behind it. */
        void unlockMilestone(String milestone);
    }

    private final SmpDao dao;
    private final Track track;
    private final Identities identities;
    private final AccessReader access;
    private final Logger logger;

    public SmpAdmin(
            final SmpDao dao,
            final Track track,
            final Identities identities,
            final AccessReader access,
            final Logger logger) {
        this.dao = dao;
        this.track = track;
        this.identities = identities;
        this.access = access;
        this.logger = logger;
    }

    /** Closes one open objective of the active milestone, paying out what was collected. */
    public Answer completeObjective(final String key) {
        final Optional<String> active = dao.activeMilestoneKey();
        if (active.isEmpty()) {
            return Answer.refused(ServerRefusal.NO_ACTIVE_MILESTONE.with());
        }
        final Optional<ObjectiveRow> row = dao.objective(active.get(), key).filter(objective -> !objective.completed());
        if (row.isEmpty()) {
            return Answer.refused(ServerRefusal.NO_SUCH_OBJECTIVE.with(key));
        }
        track.finishObjective(active.get(), row.get());
        logger.info("an admin completed objective " + active.get() + "/" + key);
        return Answer.done(MESSAGES.smp().admin().objectiveCompleted(key, new MilestoneContext(active.get())));
    }

    /** Unlocks the active milestone by hand and pays everybody who qualified. */
    public Answer unlockMilestone(final String key) {
        final Optional<String> active = dao.activeMilestoneKey();
        if (active.isEmpty()) {
            return Answer.refused(ServerRefusal.NO_ACTIVE_MILESTONE.with());
        }
        if (!active.get().equals(key)) {
            return Answer.refused(ServerRefusal.MILESTONE_NOT_ACTIVE.with(key, active.get()));
        }
        track.unlockMilestone(key);
        logger.info("an admin unlocked milestone " + key);
        return Answer.done(MESSAGES.smp().admin().milestoneUnlocked(key));
    }

    /** Adds or removes aura for an online player, recording that the console did it. */
    public Answer changeAura(final UUID player, final String name, final int delta) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player).or(() -> dao.discordIdOf(player));
        if (discordId.isEmpty()) {
            return Answer.failed(MESSAGES.smp().admin().targetUnlinked(new PlayerContext(name)));
        }
        try {
            dao.addAura(discordId.get(), delta, AuraReason.ADMIN.stored(), "by the console");
        } catch (final RuntimeException failure) {
            // Its own answer: the aura row may be booked although reading the new total failed.
            logger.log(java.util.logging.Level.WARNING, "the aura change for " + name + " failed", failure);
            return Answer.failed(MESSAGES.smp().admin().auraUnknown(new PlayerContext(name), delta));
        }
        dao.auraOf(discordId.get()).ifPresent(now -> identities.recordAura(player, now));
        logger.info("the console changed " + name + "'s aura by " + delta);
        return Answer.done(MESSAGES.smp().admin().auraChanged(new PlayerContext(name), delta));
    }

    /** Tells the console whether somebody is linked, has access and is halfway through buying it. */
    public void showAccess(final PaperUser console, final UUID player, final String name) {
        final AccessState state = access.accessState(player);
        final DiscordId discordId = state.discordId();
        if (discordId == null) {
            // An unlinked account should not have got past the proxy at all.
            console.reply(MESSAGES.smp().access().unlinked(new PlayerContext(name)), Tone.BAD);
            return;
        }
        console.reply(
                MESSAGES.smp().access().linked(new PlayerContext(name), new DiscordMemberContext(discordId.value())),
                Tone.NEUTRAL);
        if (state.accessActive() && state.accessValidUntil() != null) {
            console.reply(MESSAGES.smp().access().active(SeasonDates.format(state.accessValidUntil())), Tone.GOOD);
        } else if (state.accessValidUntil() != null) {
            console.reply(MESSAGES.smp().access().expired(SeasonDates.format(state.accessValidUntil())), Tone.WARN);
        } else {
            console.reply(MESSAGES.smp().access().never(), Tone.WARN);
        }
        final Optional<OpenPayment> pending = access.openPayment(discordId);
        pending.ifPresentOrElse(
                payment -> console.reply(
                        // A request with no bunq tab is somebody who picked a number of days and never got as far.
                        payment.hasTab()
                                ? MESSAGES.smp()
                                        .access()
                                        .payment(
                                                payment.reference(),
                                                payment.days(),
                                                payment.amount(),
                                                SeasonDates.format(payment.created()))
                                : MESSAGES.smp()
                                        .access()
                                        .paymentUnstarted(
                                                payment.reference(),
                                                payment.days(),
                                                SeasonDates.format(payment.created())),
                        Tone.NEUTRAL),
                () -> console.reply(MESSAGES.smp().access().noPayment(), Tone.MUTED));
    }
}
