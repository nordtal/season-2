package eu.nordtal.s2.hungergames.db;

/** {@code hg_member.state}, mirrored from V1__schema.sql's CHECK constraint. */
public enum MemberState {
    OWNER,
    INVITED,
    ACCEPTED,
    DECLINED
}
