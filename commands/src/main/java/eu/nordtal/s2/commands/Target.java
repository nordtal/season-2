package eu.nordtal.s2.commands;

/**
 * Which process runs a command's effect.
 *
 * <h2>Why a command has a target at all</h2>
 * Because the effect is bound to a JVM and no abstraction removes that. {@code /smp farmreset}
 * deletes a world, so it can only run where that world is open; {@code /hg start} releases players
 * from a lobby that exists in one process. The front half of a command - who is asking, may they, in
 * which language - is the same everywhere, which is what {@link NordtalUser} is for. The back half
 * has an address, and this is it.
 *
 * <p>So a command asked for from Discord does not "run in Discord". It becomes a row addressed to
 * one of these, the process that owns it claims the row, and the answer comes back through the same
 * row - the shape {@code update_request} has used since 2026-09-01.</p>
 *
 * <h2>{@link #BOT} is not an exception to that</h2>
 * {@code /grant-access} and {@code /settle} really do run in the bot: they touch Discord roles and
 * bunq, which no Paper server can reach. They are targets like any other, and the reason they never
 * become a row is that the surface asking for them is usually the process that owns them - not that
 * they are a different kind of command.
 */
public enum Target {

    /** The SMP backend. Worlds, aura, milestones, POIs, graves. */
    SMP,

    /** The hunger games backend. The lobby, the game, the border. */
    HUNGER_GAMES,

    /** The waiting room. Almost nothing lives here, which is the point of it. */
    LIMBO,

    /** The Velocity proxy. Phase, routing, the login gate, the pack station. */
    PROXY,

    /** The Discord bot. Access, payments, roles - the things only it can reach. */
    BOT;

    /**
     * How this process is named to somebody who is waiting for it - "the SMP server".
     *
     * <h2>Why a switch and not {@code "command.target." + name()}</h2>
     * Because the concatenation was exactly that, and {@code MessageBundlesTest} could see only the
     * prefix: a key it cannot read is a key it cannot check, and the way an unchecked key surfaces
     * is the literal string {@code command.target.SMP} in a Discord message, at the moment somebody
     * is being told why their command did not run. Spelling the five out makes the test able to
     * confirm all five exist in both languages, and makes the compiler demand a sixth when a sixth
     * process appears.
     */
    public String messageKey() {
        return switch (this) {
            case SMP -> "command.target.SMP";
            case HUNGER_GAMES -> "command.target.HUNGER_GAMES";
            case LIMBO -> "command.target.LIMBO";
            case PROXY -> "command.target.PROXY";
            case BOT -> "command.target.BOT";
        };
    }
}
