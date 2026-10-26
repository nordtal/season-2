package eu.nordtal.season.smp.command;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Money;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.PaperUser;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.TrackDao;
import eu.nordtal.season.smp.wheel.SpinDao;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The admin actions of the SMP. The console and Steward, through the inbox, run the same actions.
 *
 * Every method reads the database, so none runs on the main thread.
 */
public final class SmpAdmin {

    public record Who(PlayerContext player) {}

    public record AuraSet(@Example("250") long aura) {}

    public record SpinsSet(@Example("3") long spins) {}

    public record Milestone(@Example("the-nether") String key) {}

    public record Objective(@Example("mine-diamonds") String key, MilestoneContext milestone) {}

    public record Linked(PlayerContext player, DiscordMemberContext discord) {}

    public record Until(@Example("2026-11-22T18:00:00Z") Instant until) {}

    public record Since(@Example("2026-10-24T18:00:00Z") Instant since) {}

    public record Payment(
            @Example("NT-A1B2C3") String reference,
            @Example("30") long days,
            @Example("500") Money amount,
            @Example("2026-10-24T18:00:00Z") Instant since) {}

    public record PaymentUnstarted(
            @Example("NT-A1B2C3") String reference,
            @Example("30") long days,
            @Example("2026-10-31T18:00:00Z") Instant since) {}

    public static final DocumentSpec<DocumentSpec.None> PLAYER_OFFLINE =
            admin("player-offline", DocumentSpec.None.class).titled("Player not online");
    public static final DocumentSpec<DocumentSpec.None> NAME_TAGS_REDRAWN =
            admin("name-tags-redrawn", DocumentSpec.None.class).titled("Name tags redrawn");
    static final DocumentSpec<Objective> OBJECTIVE_COMPLETED =
            admin("objective-completed", Objective.class).titled("Objective closed by hand");
    static final DocumentSpec<Milestone> MILESTONE_UNLOCKED =
            admin("milestone-unlocked", Milestone.class).titled("Milestone unlocked by hand");
    static final DocumentSpec<AuraSet> AURA_SET =
            admin("aura-set", AuraSet.class).titled("Aura set");
    static final DocumentSpec<SpinsSet> SPINS_SET =
            admin("spins-set", SpinsSet.class).titled("Extra spins set");

    static final DocumentSpec<Who> UNLINKED = access("unlinked", Who.class).titled("No linked Discord account");
    static final DocumentSpec<Linked> LINKED = access("linked", Linked.class).titled("Linked Discord account");
    static final DocumentSpec<Until> ACTIVE = access("active", Until.class).titled("Access active");
    static final DocumentSpec<Since> EXPIRED = access("expired", Since.class).titled("Access expired");
    static final DocumentSpec<DocumentSpec.None> NEVER =
            access("never", DocumentSpec.None.class).titled("Access never bought");
    static final DocumentSpec<Payment> PAYMENT =
            access("payment", Payment.class).titled("Open purchase with a payment link");
    /** A request without a bunq tab belongs to a player who chose a number of days only. */
    static final DocumentSpec<PaymentUnstarted> PAYMENT_UNSTARTED =
            access("payment-unstarted", PaymentUnstarted.class).titled("Open purchase without a payment link");

    static final DocumentSpec<DocumentSpec.None> NO_PAYMENT =
            access("no-payment", DocumentSpec.None.class).titled("No open purchase");

    public interface Track {

        /** Closes {@code objective} of {@code milestone} and pays out what was collected. No player is the actor. */
        void finishObjective(String milestone, ObjectiveRow objective);

        /** Unlocks {@code milestone} and pays every player who qualified. No player is the actor. */
        void unlockMilestone(String milestone);
    }

    private final TrackDao rows;
    private final AuraDao aura;
    private final SpinDao spins;
    private final Track track;
    private final AccessReader access;
    private final Logger logger;

    public SmpAdmin(
            final TrackDao rows,
            final AuraDao aura,
            final SpinDao spins,
            final Track track,
            final AccessReader access,
            final Logger logger) {
        this.rows = rows;
        this.aura = aura;
        this.spins = spins;
        this.track = track;
        this.access = access;
        this.logger = logger;
    }

    public Answer completeObjective(final String key) {
        final Optional<String> active = rows.activeMilestoneKey();
        if (active.isEmpty()) {
            return Answer.refused(ServerRefusal.NO_ACTIVE_MILESTONE.with());
        }
        final Optional<ObjectiveRow> row =
                rows.objective(active.get(), key).filter(objective -> !objective.completed());
        if (row.isEmpty()) {
            return Answer.refused(ServerRefusal.NO_SUCH_OBJECTIVE.with(key));
        }
        track.finishObjective(active.get(), row.get());
        logger.info("an admin completed objective " + active.get() + "/" + key);
        return Answer.done(OBJECTIVE_COMPLETED.with(new Objective(key, new MilestoneContext(active.get()))));
    }

    public Answer unlockMilestone(final String key) {
        final Optional<String> active = rows.activeMilestoneKey();
        if (active.isEmpty()) {
            return Answer.refused(ServerRefusal.NO_ACTIVE_MILESTONE.with());
        }
        if (!active.get().equals(key)) {
            return Answer.refused(ServerRefusal.MILESTONE_NOT_ACTIVE.with(key, active.get()));
        }
        track.unlockMilestone(key);
        logger.info("an admin unlocked milestone " + key);
        return Answer.done(MILESTONE_UNLOCKED.with(new Milestone(key)));
    }

    /** Sets the aura of {@code person} and records the difference as a change by {@code actor}. */
    public Answer setAura(final DiscordId person, final int value, final Actor actor) {
        final int delta = aura.setAura(person, value, new AuraCause.Admin(actor));
        logger.info(actor + " set the aura of " + person.value() + " to " + value + ", a change of " + delta);
        return Answer.done(AURA_SET.with(new AuraSet(value)));
    }

    /** Sets the extra spins {@code person} has left. */
    public Answer setSpins(final DiscordId person, final int value) {
        spins.setSpins(person, value);
        logger.info("the extra spins of " + person.value() + " are set to " + value);
        return Answer.done(SPINS_SET.with(new SpinsSet(value)));
    }

    public void showAccess(final PaperUser console, final UUID player, final String name) {
        final AccessState state = access.accessState(player);
        final DiscordId discordId = state.discordId();
        final PlayerContext who = PlayerContext.of(PlayerId.of(player), name);
        if (discordId == null) {
            console.reply(UNLINKED.with(new Who(who)));
            return;
        }
        console.reply(LINKED.with(new Linked(who, new DiscordMemberContext(discordId, discordId.value()))));
        if (state.accessActive() && state.accessValidUntil() != null) {
            console.reply(ACTIVE.with(new Until(state.accessValidUntil())));
        } else if (state.accessValidUntil() != null) {
            console.reply(EXPIRED.with(new Since(state.accessValidUntil())));
        } else {
            console.reply(Sent.of(NEVER));
        }
        access.openPayment(discordId)
                .ifPresentOrElse(
                        payment -> console.reply(
                                payment.hasTab()
                                        ? PAYMENT.with(new Payment(
                                                payment.reference(),
                                                payment.days(),
                                                Money.euroCents(payment.amountCents()),
                                                payment.created()))
                                        : PAYMENT_UNSTARTED.with(new PaymentUnstarted(
                                                payment.reference(), payment.days(), payment.created()))),
                        () -> console.reply(Sent.of(NO_PAYMENT)));
    }

    private static <V extends Record> DocumentSpec<V> admin(final String name, final Class<V> values) {
        return DocumentSpec.game("smp", "admin-" + name, "Admin", DocumentSpec.Shape.CHAT, values);
    }

    private static <V extends Record> DocumentSpec<V> access(final String name, final Class<V> values) {
        return DocumentSpec.game("smp", "access-" + name, "Access", DocumentSpec.Shape.CHAT, values);
    }
}
