package eu.nordtal.s2.database.notify;

import java.util.Objects;

/**
 * Every {@code LISTEN}/{@code NOTIFY} channel in the network; a {@link SignalHub} registers by one of these.
 * The payload is an id at most and never the state; each constant names who emits and who listens.
 */
public enum Channel {

    /** The season phase moved. Emitted by the phase directory; the proxy, smp, hunger-games and the bot listen. */
    PHASE("nordtal_phase"),

    /** An admin flag in {@code discord_user} was written; payload the Discord id. The proxy, bot and Paper listen. */
    ADMIN("nordtal_admin"),

    /** A request in a Minecraft server's inbox was written or moved on. The Paper servers and the proxy listen. */
    SERVER("nordtal_server"),

    /** A run was asked for, moved on or settled. steward, the proxy and the bot's update feed listen. */
    UPDATE("nordtal_update"),

    /** A {@code payment_request} row was written. steward and discord-bot listen. */
    PAYMENT("nordtal_payment"),

    /** A request in the bot's inbox was written or moved on. discord-bot listens. */
    BOT("nordtal_bot"),

    /** The SMP track, its progress or the aura board moved. smp emits and its surfaces listen. */
    SMP("nordtal_smp"),

    /** A stored setting changed; payload its service. steward emits, every process with settings listens. */
    SETTINGS("nordtal_settings");

    private final String sqlName;

    Channel(final String sqlName) {
        // The name goes into LISTEN unquoted: an identifier has no placeholder.
        if (!Objects.requireNonNull(sqlName, "sqlName").matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("not a usable LISTEN channel name: '" + sqlName + "'");
        }
        this.sqlName = sqlName;
    }

    /** Returns the channel's name as the SQL spells it in {@code LISTEN} and {@code pg_notify}. */
    public String sqlName() {
        return sqlName;
    }
}
