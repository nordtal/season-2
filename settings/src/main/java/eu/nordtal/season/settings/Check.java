package eu.nordtal.season.settings;

/** What a valid group of settings is beyond its types; it throws {@link IllegalArgumentException} to refuse one. */
@FunctionalInterface
public interface Check<T> {

    /**
     * Refuses {@code values} by throwing.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    void check(T values);

    /** Returns a check that refuses nothing. */
    static <T> Check<T> none() {
        return values -> {};
    }
}
