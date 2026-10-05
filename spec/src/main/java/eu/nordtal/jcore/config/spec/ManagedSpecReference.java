package eu.nordtal.jcore.config.spec;

import org.jspecify.annotations.Nullable;

/**
 * Not part of the vendored Spec library, though it uses its package-private {@link SpecProxy}.
 *
 * Spec's own {@link SpecReference} owns the load/save sequence itself. jcore needs that sequence
 * to also do unknown-key detection, atomic writes, the environment overlay, validation and
 * locking, so this holds only the two things that must live inside the Spec package: the proxy
 * that keeps {@code @Reload} and {@code @Save} working on the spec interface, and the swappable
 * value behind it.
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

    /**
     * The stable instance handed to callers. It always reads through to the current value.
     *
     * @return the stable proxy instance
     */
    public T get() {
        return proxy;
    }

    /**
     * Swaps in a freshly loaded value.
     *
     * @param value the new current value
     */
    public void set(final T value) {
        this.value = value;
    }

    /**
     * The current underlying value, or {@code null} before the first load.
     *
     * @return the current underlying value, or {@code null}
     */
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
