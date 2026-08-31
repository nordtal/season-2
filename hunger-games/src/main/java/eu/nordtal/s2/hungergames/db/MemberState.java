package eu.nordtal.s2.hungergames.db;

/** {@code hg_member.state}, mirrored from V5__hunger_games.sql's CHECK constraint. */
public enum MemberState {
    OWNER,
    INVITED,
    ACCEPTED,
    DECLINED
}
