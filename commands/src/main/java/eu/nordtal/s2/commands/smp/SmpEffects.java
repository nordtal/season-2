package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.common.access.OpenPayment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything {@code /smp} touches that only the SMP server can reach.
 *
 * <h2>Where the line is drawn</h2>
 * Nothing here decides anything. Which sentence comes back when no milestone is active, whether an
 * unknown objective key is worth a database round trip, what a correction of zero aura should do -
 * all of that is in the command classes, where it can be asserted without a world. What is left is
 * the work itself, and every one of these needs something bound to this JVM: a world folder, the
 * milestone engine's in-memory track, the identity cache the nametags are drawn from.
 *
 * <h2>Two instances of this exist per server, and the difference matters</h2>
 * One built with the plugin's async scheduler, for {@code /smp} typed in chat; one built with
 * {@code Runnable::run}, for the command inbox - which settles a request row the moment the command
 * returns and would otherwise write an empty answer. {@code CommandInbox#register} refuses the wrong
 * one at startup rather than letting it be discovered on the surface furthest from the logs.
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
    record Access(String discordId, boolean accessActive, Instant validUntil) {
    }

    /** Re-read the reloadable configs and the message bundles. */
    void reload();

    /**
     * Delete the farm world folder and regenerate it.
     *
     * <p>The one command in this repository that destroys something a player can be standing in,
     * which is why {@code deploy/minecraft/entrypoint-test.sh} exists at all.</p>
     */
    void resetFarmWorld();

    /** The active milestone's key, or empty when the track has not started or is finished. */
    Optional<String> activeMilestone();

    /** Whether that milestone declares an objective by this key. */
    boolean hasObjective(String milestone, String objective);

    /**
     * Close one objective by hand, paying out scaled to what was actually collected.
     *
     * <p>Never the full pot - that is what makes an escape hatch never worth more than doing the
     * work, and it is the same arithmetic a real completion uses.</p>
     */
    void completeObjective(String milestone, String objective);

    /** Unlock a whole milestone by hand. */
    void unlockMilestone(String milestone);

    /** The name of a player this server knows, for a sentence about them. */
    Optional<String> nameOf(UUID player);

    /** The Discord account linked to a Minecraft one. Empty means the link is missing. */
    Optional<String> discordIdOf(UUID player);

    /**
     * Change somebody's aura and record who did it.
     *
     * @param by a name for the audit trail. An unexplained balance is what the reason column exists
     *           to prevent, and an admin's correction is the likeliest one to be questioned
     */
    void changeAura(UUID player, String discordId, int delta, String by);

    /** Whether this account is linked and whether access is running. Empty when nothing is known. */
    Optional<Access> access(UUID player);

    /** The purchase somebody has started and not finished, if there is one. */
    Optional<OpenPayment> openPayment(String discordId);
}
