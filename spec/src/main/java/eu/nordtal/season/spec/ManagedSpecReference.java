package eu.nordtal.season.spec;

import org.jspecify.annotations.Nullable;

/**
 * Holds the value of a spec behind the proxy and keeps {@code @Reload} and {@code @Save} working.
 * Written for nordtal.eu, not part of Spec.
 *
 * @param <T> the spec interface type
 */
public final class ManagedSpecReference<T> {

    private final T proxy;
    private volatile @Nullable T value;

    /**
     * @param type     the spec interface
     * @param onReload run when a {@code @Reload} method is called on the spec
     * @param onSave   run when a {@code @Save} method is called on the spec
     */
    public ManagedSpecReference(final Class<T> type, final Runnable onReload, final Runnable onSave) {
        if (!Specs.isConfigSpec(type)) {
            throw new IllegalArgumentException(type.getName() + " must be an interface annotated with @ConfigSpec.");
        }
        this.proxy = SpecProxy.proxy(type, this::value, onReload, onSave);
    }

    public T get() {
        return proxy;
    }

    public void set(final T value) {
        this.value = value;
    }

    public @Nullable T current() {
        return value;
    }

    private T value() {
        final @Nullable T current = value;
        if (current == null) {
            throw new IllegalStateException("This configuration has not been loaded yet.");
        }
        return current;
    }
}
