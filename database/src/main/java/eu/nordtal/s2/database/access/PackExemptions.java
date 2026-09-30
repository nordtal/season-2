package eu.nordtal.s2.database.access;

import javax.sql.DataSource;

/**
 * Which players an admin lets through without the resource pack.
 *
 * An exemption lasts until an admin enforces the pack again, and takes effect at the next login.
 */
public interface PackExemptions {

    /**
     * @param dataSource a pool the caller owns
     * @return exemptions over that pool
     */
    static PackExemptions using(final DataSource dataSource) {
        return new JdbiPackExemptions(dataSource);
    }

    /**
     * {@code actor} lets {@code target} play without the resource pack.
     *
     * @return what happened; nothing is written unless it is {@link Outcome#CHANGED}
     */
    Outcome exempt(String actor, String target);

    /**
     * {@code actor} makes the resource pack required for {@code target} again.
     *
     * @return what happened; nothing is written unless it is {@link Outcome#CHANGED}
     */
    Outcome enforce(String actor, String target);

    /** What an exemption or an enforcement did. */
    enum Outcome {
        /** Written. */
        CHANGED,
        /** The account already was in that state. */
        UNCHANGED,
        /** The one who clicked is no longer an admin. */
        ACTOR_NOT_ADMIN,
        /** Nobody by that Discord id is known. */
        UNKNOWN
    }
}
