package eu.nordtal.season.discordbot.onboarding;

/** What the bot answers a member who has just chosen a language or a region in the onboarding channel. */
enum Answer {

    /** The language is given and no region held: the region is asked next, in that language. */
    ASK_REGION,

    /** Both are held now. */
    SAVED,

    /** The choice could not be given, or there is no region to offer. */
    FAILED;

    /**
     * Decides the answer once the chosen role was given or refused.
     *
     * @param given whether the member holds the chosen role now
     * @param regionHeld whether the member holds a region, the one just chosen included
     * @param regionOffered whether any region has a role to choose
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
