package eu.nordtal.season.database.notify;

import java.util.Objects;

/**
 * A {@code LISTEN}/{@code NOTIFY} channel of the network. A {@link SignalHub} registers by channel.
 * The payload is at most an id, never the state.
 */
public enum Channel {

    /** The season phase changed. The phase directory emits, the proxy, smp, hunger-games and the bot listen. */
    PHASE("nordtal_phase"),

    /**
     * An admin flag in {@code discord_user} changed. The payload is the Discord id.
     * The proxy, the bot and Paper listen.
     */
    ADMIN("nordtal_admin"),

    /** A request in the inbox of a Minecraft server changed. smp, hunger-games and steward listen. */
    SERVER("nordtal_server"),

    /** A run was requested, changed or finished. steward, the proxy and the bot's update feed listen. */
    UPDATE("nordtal_update"),

    /** A {@code payment_request} row was written. steward and discord-bot listen. */
    PAYMENT("nordtal_payment"),

    /** A request in the bot's inbox changed. discord-bot listens. */
    BOT("nordtal_bot"),

    /**
     * A Hunger Games round, team, member, game or event row was written. discord-bot and hunger-games emit.
     * The proxy listens, for its server list.
     */
    HUNGER_GAMES("nordtal_hunger_games"),

    /** The SMP track, its progress or the aura board changed. smp emits and its surfaces listen. */
    SMP("nordtal_smp"),

    /** A stored setting changed. The payload is its service. steward emits, every process with settings listens. */
    SETTINGS("nordtal_settings"),

    /**
     * A message override or an admin's document changed. The payload is its bundle or namespace.
     * steward emits, every process with bundles listens.
     */
    MESSAGES("nordtal_messages"),

    /** An admin alert was raised. steward and the bot emit, steward listens and routes it. */
    ALERT("nordtal_alert"),

    /** A server exported its game data or the agent drew the icons of a version. steward and the agent listen. */
    GAME_DATA("nordtal_game_data"),

    /** A server or the proxy published a changed command tree. The payload is its service. steward listens. */
    COMMAND_TREE("nordtal_command_tree");

    private final String sqlName;

    Channel(final String sqlName) {
        // LISTEN takes the name unquoted, and an identifier has no placeholder.
        if (!Objects.requireNonNull(sqlName, "sqlName").matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("not a usable LISTEN channel name: '" + sqlName + "'");
        }
        this.sqlName = sqlName;
    }

    public String sqlName() {
        return sqlName;
    }
}
