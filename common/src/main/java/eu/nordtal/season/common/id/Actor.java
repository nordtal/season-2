package eu.nordtal.season.common.id;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Who asked for something. {@code actor_kind} and {@code actor_id} store it, and the internal wire carries it.
 *
 * @param person the Discord id if {@code kind} is {@link Kind#PERSON}, otherwise {@code null}
 */
public record Actor(Kind kind, @Nullable DiscordId person) {

    /** Steward itself, for example the nightly backup. */
    public static final Actor STEWARD = new Actor(Kind.STEWARD, null);

    /** Somebody on the host, through the installer. */
    public static final Actor HOST = new Actor(Kind.HOST, null);

    /** The Discord bot itself. Only the journal records it. */
    public static final Actor BOT = new Actor(Kind.BOT, null);

    public enum Kind {

        /** A signed-in person, named by {@link Actor#person()}. */
        PERSON,

        /** Steward itself. */
        STEWARD,

        /** The installer on the host. */
        HOST,

        BOT
    }

    public Actor {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.PERSON) != (person != null)) {
            throw new IllegalArgumentException("a person carries a Discord id and nothing else does: " + kind);
        }
    }

    public static Actor person(final DiscordId id) {
        return new Actor(Kind.PERSON, Objects.requireNonNull(id, "id"));
    }

    /** Returns the actor stored as {@code actor_kind} {@code kind} and {@code actor_id} {@code id}. */
    public static Actor of(final String kind, final @Nullable String id) {
        return new Actor(Kind.valueOf(kind), DiscordId.ofNullable(id));
    }

    public @Nullable String id() {
        return person == null ? null : person.value();
    }
}
