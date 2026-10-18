package eu.nordtal.season.database.registration;

/** The {@code team_member.state} of a person in a team. */
public enum Membership {
    OWNER,

    /** The owner invited the person, who has not answered and is not a participant. */
    INVITED,

    ACCEPTED,

    DECLINED
}
