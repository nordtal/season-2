package eu.nordtal.season.database.registration;

/** The {@code team_member.state} of a person in a team. */
public enum Membership {

    /** The person registered the team. */
    OWNER,

    /** The owner invited the person, who has not answered and is not a participant. */
    INVITED,

    /** The person accepted the invite. */
    ACCEPTED,

    /** The person declined the invite. */
    DECLINED
}
