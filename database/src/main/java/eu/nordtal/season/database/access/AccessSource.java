package eu.nordtal.season.database.access;

/** Where an {@link AccessGrant} came from, stored in {@code access_grant.source}. */
public enum AccessSource {

    /** Paid for through a {@code payment_request}, which {@code payment_request_id} names. */
    PURCHASE,

    /** Granted by an admin with {@code /grant-access}, without a payment request. */
    ADMIN
}
