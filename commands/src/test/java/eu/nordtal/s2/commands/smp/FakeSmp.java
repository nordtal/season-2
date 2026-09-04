package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.common.access.OpenPayment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The SMP server, as far as a {@code /smp} command can tell. */
final class FakeSmp implements SmpEffects {

    /** Every effect that actually happened, in order. */
    final List<String> did = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    final Map<UUID, String> names = new LinkedHashMap<>();
    final Map<UUID, String> links = new LinkedHashMap<>();

    String activeMilestone;
    List<String> objectives = new ArrayList<>();
    Access access;
    OpenPayment payment;

    /** Set to make the next effect throw, for the "the database stopped answering" branches. */
    RuntimeException failure;

    /**
     * The same, for the open-payment read alone.
     *
     * <p>Separate because the two reads behind {@code /smp access} are separate on purpose, and the
     * property worth pinning is that losing the second does not discard the first.</p>
     */
    RuntimeException paymentFailure;

    @Override
    public void async(final Runnable work) {
        // Inline, like the inbox's. A command's whole answer has to exist by the time run()
        // returns, and a test that scheduled it would be asserting against a race.
        work.run();
    }

    @Override
    public void warn(final String what, final Throwable cause) {
        warnings.add(what);
    }

    @Override
    public void reload() {
        throwIfAsked();
        did.add("reload");
    }

    @Override
    public void resetFarmWorld() {
        throwIfAsked();
        did.add("farmreset");
    }

    @Override
    public Optional<String> activeMilestone() {
        throwIfAsked();
        return Optional.ofNullable(activeMilestone);
    }

    @Override
    public boolean hasObjective(final String milestone, final String objective) {
        return objectives.contains(objective);
    }

    @Override
    public void completeObjective(final String milestone, final String objective) {
        did.add("complete " + milestone + "/" + objective);
    }

    @Override
    public void unlockMilestone(final String milestone) {
        throwIfAsked();
        did.add("unlock " + milestone);
    }

    @Override
    public Optional<String> nameOf(final UUID player) {
        return Optional.ofNullable(names.get(player));
    }

    @Override
    public Optional<String> discordIdOf(final UUID player) {
        throwIfAsked();
        return Optional.ofNullable(links.get(player));
    }

    @Override
    public void changeAura(final UUID player, final String discordId, final int delta,
                           final String by) {
        throwIfAsked();
        did.add("aura " + discordId + " " + delta + " by " + by);
    }

    @Override
    public Optional<Access> access(final UUID player) {
        throwIfAsked();
        return Optional.ofNullable(access);
    }

    @Override
    public Optional<OpenPayment> openPayment(final String discordId) {
        if (paymentFailure != null) {
            throw paymentFailure;
        }
        throwIfAsked();
        return Optional.ofNullable(payment);
    }

    static OpenPayment payment(final boolean hasTab) {
        return new OpenPayment("NT-A1B2C3", 60, 1000, 0, hasTab, Instant.parse("2026-09-01T10:00:00Z"));
    }

    private void throwIfAsked() {
        if (failure != null) {
            throw failure;
        }
    }
}
