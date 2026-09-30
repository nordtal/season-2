package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.database.access.OpenPayment;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything {@code /smp} touches that only the SMP server can reach; nothing here decides anything.
 *
 * The inbox's instance must run {@code async} inline, which {@code CommandInbox#register} checks at startup.
 */
public interface SmpEffects extends CommandEffects {

    /**
     * What {@code /smp access} needs, in one read.
     *
     * @param discordId    the linked Discord account, or {@code null} when there is none
     * @param accessActive whether access is running right now
     * @param validUntil   when the current or last period ends, or {@code null} if there never was
     *                     one
     */
    record Access(
            @Nullable String discordId,
            boolean accessActive,
            @Nullable Instant validUntil) {}

    /**
     * Re-reads the reloadable configs and the message bundles.
     *
     * @return why the milestone track was refused and left running as it was, one line each; empty when it was taken
     */
    java.util.List<String> reload();

    /** Returns the active milestone's key, or empty when the track has not started or is finished. */
    Optional<String> activeMilestone();

    /** Returns whether that milestone declares an objective by this key. */
    boolean hasObjective(String milestone, String objective);

    /** Closes one objective by hand, paying out scaled to what was actually collected. */
    void completeObjective(String milestone, String objective);

    /** Unlocks a whole milestone by hand. */
    void unlockMilestone(String milestone);

    /** Returns the name of a player this server knows. */
    Optional<String> nameOf(UUID player);

    /** Returns the Discord account linked to a Minecraft one, empty when the link is missing. */
    Optional<String> discordIdOf(UUID player);

    /**
     * Changes somebody's aura and records who did it.
     *
     * @param by a name for the audit trail
     */
    void changeAura(UUID player, String discordId, int delta, String by);

    /** Returns whether this account is linked and has access running, empty when nothing is known. */
    Optional<Access> access(UUID player);

    /** Returns the purchase somebody has started and not finished, if there is one. */
    Optional<OpenPayment> openPayment(String discordId);
}
