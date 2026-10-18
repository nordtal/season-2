package eu.nordtal.season.discordbot.onboarding;

/** The bot's answer to a member who chose a language or a region in the onboarding channel. */
enum Answer {

    /** The member holds the chosen language and no region. The bot asks for the region in that language. */
    ASK_REGION,

    SAVED,

    /** The role was not given, or no region has a role. */
    FAILED;

    /**
     * Returns the answer after the chosen role was given or refused.
     *
     * @param given whether the member holds the chosen role
     * @param regionHeld whether the member holds a region, including the chosen one
     * @param regionOffered whether any region has a role
     */
    static Answer after(final boolean given, final boolean regionHeld, final boolean regionOffered) {
        if (!given) {
            return FAILED;
        }
        if (regionHeld) {
            return SAVED;
        }
        return regionOffered ? ASK_REGION : FAILED;
    }
}
