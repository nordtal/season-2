package eu.nordtal.season.database.registration;

/** {@code team_member.state}: how a person stands with a team. */
public enum Membership {

    /** Registered the team. */
    OWNER,

    /** Asked by the owner and not answered yet; not a participant. */
    INVITED,

    /** Said yes to the owner's invite. */
    ACCEPTED,

    /** Said no; the row stays so the invite cannot be answered twice. */
    DECLINED
}
