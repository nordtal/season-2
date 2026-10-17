package eu.nordtal.season.database.audit;

/**
 * What a journal entry records, the {@code journal_entry.action} a writer files it under and the journal filters by.
 * Each has its line in {@link eu.nordtal.season.database.AdminTexts}; a new one is added to that column's check too.
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
    ADD_AUTHENTICATOR,
    USED_AUTHENTICATOR,
    REMOVE_AUTHENTICATOR,
    FORGET_FACTORS,
    WEB_PUSH_SUBSCRIBE,
    WEB_PUSH_UNSUBSCRIBE,
    WEB_PUSH_TEST,
    SET_ALERT_PREFERENCE,
    CONSOLE,
    SAVE_SETTINGS,
    SAVE_MESSAGES,
    ADD_PLUGIN,
    REMOVE_PLUGIN,
    REQUEST_RUN,
    CANCEL_RUN,
    DOWNLOAD_BACKUP,
    ANNOUNCE,
    COMPLETE_OBJECTIVE,
    UNLOCK_MILESTONE,
    START_GAME,
    UNANCHORED_GRANT,
    LINK_CODES_EXHAUSTED
}
