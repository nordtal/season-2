package eu.nordtal.s2.steward.ui.push;

import org.jetbrains.annotations.NotNull;

/**
 * The traffic light's state, as {@code AlertWatch} compares it from one poll to the next.
 *
 * <h2>Why this is not steward-worker's {@code AlertLevel.Reading}</h2>
 * That type lives in {@code :steward-worker} and this module does not depend on it - the two
 * services talk JSON over {@code /api/alert-level}, the same boundary {@link InternalClient}'s own
 * class note draws for every other route. A shared Java type here would mean the jar of one service
 * on the classpath of the other, which is exactly the coupling {@code :steward-ui}'s test-only
 * dependency on {@code :steward-worker} already warns against outside of tests.
 *
 * <p>Three strings rather than an enum for {@code level}: the worker already turns its own
 * {@code AlertLevel.Level} into lowercase text before this ever sees it, and a second enum here
 * would be a second place that has to agree with the first on spelling. Equality is what
 * {@link #equals} already gives a record - which is the whole of what {@code AlertWatch} needs to
 * ask "did anything change since last time".</p>
 */
public record AlertReading(@NotNull String level, @NotNull String subject, @NotNull String path) {
}
