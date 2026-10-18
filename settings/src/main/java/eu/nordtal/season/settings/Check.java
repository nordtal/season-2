package eu.nordtal.season.settings;

/** Validates a group of settings beyond its types by throwing {@link IllegalArgumentException}. */
@FunctionalInterface
public interface Check<T> {

    /**
     * @throws IllegalArgumentException naming the first wrong value
     */
    void check(T values);

    static <T> Check<T> none() {
        return values -> {};
    }
}
