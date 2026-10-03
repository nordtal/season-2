package eu.nordtal.s2.database.audit;

/**
 * What a journal line records, the {@code audit_log.action} a writer files it under and the journal filters by.
 * Each has its line in {@link eu.nordtal.s2.database.AdminTexts}; a row from an older build may name one not listed.
 */
public enum JournalAction {
    ADMIN_ROOT,
    GRANT_ADMIN,
    REVOKE_ADMIN,
    ENFORCE_PACK,
    EXEMPT_PACK,
    GRANT_ACCESS,
    REVOKE_ACCESS,
    SET_PLAYTIME,
    LINK,
    UNLINK,
    SETTLE,
    SET_PHASE,
    SET_LAUNCH,
    SET_SMP_START,
    REGISTER_KEY,
    HELD_KEY,
    RENAME_KEY,
    REMOVE_KEY,
    FORGET_FACTORS,
    WEB_PUSH_SUBSCRIBE,
    WEB_PUSH_UNSUBSCRIBE,
    WEB_PUSH_TEST,
    SET_ALERT_PREFERENCE,
    CONSOLE,
    SAVE_SETTINGS,
    SAVE_MESSAGES,
    ADD_PLUGIN,
    CANCEL_RUN,
    ANNOUNCE,
    COMPLETE_OBJECTIVE,
    UNLOCK_MILESTONE,
    START_GAME
}
