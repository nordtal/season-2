package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;

/** Which process runs a command's effect; a command asked for elsewhere travels there as a row. */
public enum Target {

    /** The SMP backend: worlds, aura, milestones, POIs, graves. */
    SMP,

    /** The hunger games backend: the lobby, the game, the border. */
    HUNGER_GAMES,

    /** The waiting room. */
    LIMBO,

    /** The Velocity proxy: phase, routing, the login gate, the pack station. */
    PROXY,

    /** The Discord bot: access, payments, roles. */
    BOT,

    /**
     * Wherever it was asked for, for a command whose effect touches only the database.
     *
     * {@code CatalogueTest} pins the members, since an update must not depend on a second process being alive.
     */
    LOCAL;

    /** Returns how this process is named to somebody waiting for it, spelled out so every key is checked. */
    public MessageRef message() {
        final Command.Target target = CommandMessages.MESSAGES.command().target();
        return switch (this) {
            case SMP -> target.smp();
            case HUNGER_GAMES -> target.hungerGames();
            case LIMBO -> target.limbo();
            case PROXY -> target.proxy();
            case BOT -> target.bot();
            // Never rendered: a LOCAL command never becomes a row.
            case LOCAL -> target.local();
        };
    }
}
