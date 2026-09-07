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
    BOT,

    /**
     * Wherever it was asked for. The one target that is not an address.
     *
     * <h2>Why this exists, and why it is not a hole in the rule above</h2>
     * The rule is that an effect is bound to a JVM. {@code /update}'s effect is not: it writes a row
     * into {@code update_request} and reads the answer back, and <b>every one of the five processes
     * already has that pool open</b>. There is no world to be near and no gateway to hold.
     *
     * <p>Giving it a real target would have meant {@link #BOT}, and that is worse than untidy. A
     * {@code /smp update} typed in game would become a {@code command_request} row addressed to the
     * bot, which writes an {@code update_request} row - two hops and a second process to be alive,
     * for a statement the asking server could have run itself. And the moment that matters is
     * exactly the moment it fails: <b>an update is what somebody asks for when the network is
     * already misbehaving.</b> The proxy answers {@code /phase} from its own cache before it touches
     * the database for the same reason, written down since 2026-08-31.</p>
     *
     * <p>It is deliberately not a general escape. A command belongs here only when its effect
     * touches nothing but the database - {@code CatalogueTest} pins the members, so a second one is
     * an argument somebody has to make out loud rather than a value they can pick.</p>
     */
    LOCAL;

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
            // Never rendered: a LOCAL command never becomes a row, so nothing ever waits for a
            // named process. The key exists so that the switch stays total and the bundles stay
            // symmetrical - not because a reader will see it.
            case LOCAL -> "command.target.LOCAL";
        };
    }
}
