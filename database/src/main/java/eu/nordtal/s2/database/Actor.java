package eu.nordtal.s2.database;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Who asked for a request, stored as {@code actor_kind} and {@code actor_id}, which a {@code CHECK} keeps together.
 *
 * @param kind   a person, Steward on its own, or somebody on the host
 * @param person the Discord id when {@link Kind#PERSON}, {@code null} otherwise
 */
public record Actor(Kind kind, @Nullable DiscordId person) {

    /** Nobody asked: Steward on its own, such as the nightly backup, or a server announcing its own progress. */
    public static final Actor STEWARD = new Actor(Kind.STEWARD, null);

    /** Somebody on the host, through the installer. */
    public static final Actor HOST = new Actor(Kind.HOST, null);

    /** What kind of actor, stored verbatim in {@code actor_kind}. */
    public enum Kind {

        /** A signed-in person, named by {@link Actor#person()}. */
        PERSON,

        /** Steward itself; nobody pressed anything. */
        STEWARD,

        /** The installer on the host, which works while the stack itself is broken. */
        HOST
    }

    public Actor {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.PERSON) != (person != null)) {
            throw new IllegalArgumentException("a person carries a Discord id and nothing else does: " + kind);
        }
    }

    /** Returns the person with this Discord id. */
    public static Actor person(final DiscordId id) {
        return new Actor(Kind.PERSON, Objects.requireNonNull(id, "id"));
    }

    /**
     * Reads the two columns back.
     *
     * @param kind {@code actor_kind}
     * @param id   {@code actor_id}, {@code null} unless the kind is {@code PERSON}
     */
    public static Actor of(final String kind, final @Nullable String id) {
        return new Actor(Kind.valueOf(kind), DiscordId.ofNullable(id));
    }

    /** Returns the value of {@code actor_id}, or {@code null} for anyone but a person. */
    public @Nullable String id() {
        return person == null ? null : person.value();
    }
}
