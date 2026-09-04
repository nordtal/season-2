package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.phase.SeasonDates;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * {@code /phase}, as the bot carries it out.
 *
 * <p>The mirror image of {@code network-control}'s {@code ProxyPhaseEffects}, and every difference
 * between the two is a real one:</p>
 * <ul>
 *   <li>It <b>caches nothing</b>, so {@link #observation()} is empty and the command reads the row.
 *       There is no {@code PhaseWatch} here and there should not be: the bot answers a handful of
 *       interactions a season, and a cache would be a second thing that can be stale.</li>
 *   <li>{@link #afterWrite()} therefore does nothing, for the same reason.</li>
 *   <li>Admin actions go to the <b>admin channel</b>, as a mention, like every other
 *       access-relevant action in this module. Note what it does <em>not</em> do: it never calls
 *       {@code AdminLog#record}, because {@code switchPhase} already wrote the {@code audit_log}
 *       row in the same statement and a second call would file one switch twice.</li>
 *   <li>Blocking work goes to {@code access-bot-worker}. A JDA gateway thread waiting on a database
 *       stalls the whole guild, and an interaction not acknowledged within three seconds is
 *       dead.</li>
 * </ul>
 */
@Slf4j
public final class BotPhaseEffects implements PhaseEffects {

    private final PhaseDirectory phases;
    private final AdminLog admin;
    private final ExecutorService executor;

    public BotPhaseEffects(final PhaseDirectory phases, final AdminLog admin,
                           final ExecutorService executor) {
        this.phases = phases;
        this.admin = admin;
        this.executor = executor;
    }

    @Override
    public PhaseDirectory phases() {
        return phases;
    }

    @Override
    public Optional<Observation> observation() {
        return Optional.empty();
    }

    @Override
    public void afterWrite() {
        // Nothing is cached here, so there is nothing to re-read.
    }

    @Override
    public void recordSwitch(final NordtalUser who, final PhaseChange change) {
        final String mention = "<@" + who.discordId().orElse("0") + ">";
        admin.note(change.unchanged()
                ? mention + " set the season phase to **" + change.current()
                        + "**, which it already was."
                : mention + " switched the season phase from **" + change.previous() + "** to **"
                        + change.current() + "**.");
    }

    @Override
    public void recordDate(final NordtalUser who, final boolean launch, final DateChange change) {
        final String mention = "<@" + who.discordId().orElse("0") + ">";
        admin.note(mention + " set " + (launch ? "when the network opens" : "when paid access starts")
                + " to **" + SeasonDates.format(change.current()) + "**"
                + (change.movedAccess()
                   ? ", moving " + change.grants() + " access period(s) across " + change.accounts()
                           + " account(s) with it."
                   : "."));
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        log.error("/phase failed while {}", what, failure);
        admin.alert("A /phase command failed while " + what + ": `" + failure + "`");
    }
}
