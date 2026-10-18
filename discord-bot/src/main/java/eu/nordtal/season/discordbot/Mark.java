package eu.nordtal.season.discordbot;

public enum Mark {
    DONE(Mark.PREVIEW),

    FAILED("🛑"),

    WARNING("⚠️"),

    WORKING("🔄"),

    /** A step that has not begun, or a countdown. */
    WAITING("⏳"),

    UNCHANGED("➖"),

    LOOKING("🔍"),

    PLANNED("📋"),

    STOPPED("⏹️"),

    PAID("💶"),

    /** A refusal that keeps something closed, such as too many link codes. */
    LOCKED("🔒"),

    GRANTED("🎟️"),

    REVOKED("🚫"),

    LINKED("🔗"),

    UNLINKED("✂️"),

    TIMED("⏱️"),

    NOTED("📝");

    private static final int REGIONAL_A = 0x1F1E6;

    public static final String PREVIEW = "✅";

    private final String symbol;

    Mark(final String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /**
     * Returns the flag emoji of a country.
     *
     * @param country the country's two letters, such as {@code GB}
     * @throws IllegalArgumentException if it is not two letters from A to Z
     */
    public static String flag(final String country) {
        if (!country.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("A flag's country is two letters from A to Z, was: " + country);
        }
        final StringBuilder flag = new StringBuilder();
        country.chars().forEach(letter -> flag.appendCodePoint(REGIONAL_A + letter - 'A'));
        return flag.toString();
    }

    public String before(final String text) {
        return symbol + " " + text;
    }
}
