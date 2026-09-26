package eu.nordtal.s2.common.access;

/** Where an {@link AccessGrant} came from, stored in {@code access_grant.source}. */
public enum AccessSource {

    /** Paid for through a {@code payment_request}. Carries a {@code payment_request_id}. */
    PURCHASE,

    /** Handed out by an admin with {@code /grant-access}. Has no payment request. */
    ADMIN
}
