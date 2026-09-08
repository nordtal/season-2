package eu.nordtal.s2.common.message;

/**
 * How a line should read at a glance - good news, bad news, or neither.
 *
 * <h2>Why a shared command may say this, when it may not say a colour</h2>
 * {@code :commands}' bundle carries no markup, deliberately: a MiniMessage tag in it would print as
 * literal text in Discord, and Discord's markdown would print as asterisks in chat. That rule is
 * what makes one sentence serve both surfaces and it is not being relaxed here. What it cost was the
 * one thing a wall of update output needs - a reader has to find the failed service in a list of
 * eleven lines that are otherwise identical in shape.
 *
 * <p>So the <em>meaning</em> travels and the rendering does not. A command names a tone; each
 * adapter decides what that is on its surface. Paper and Velocity colour the component through
 * {@link Tones}; Discord ignores it, because an embed has one colour for the whole of it.</p>
 *
 * <h2>Why it is here and not in {@code :commands}</h2>
 * Same reason {@code Feedback} is: this is a fact about a message, {@code :commands} is compiled
 * against no platform, and the two adapters that paint one live in two different modules. Keeping it
 * next to {@link MessageRenderer} means the painting lives next to the rendering.
 *
 * <p>It is a bare enum with no method on it on purpose. {@link Tones} is separate because it names
 * Adventure, and the Discord bot loads this type - through {@code NordtalUser}'s signature - in a
 * JVM that has no Adventure on its classpath at all.</p>
 */
public enum Tone {

    /** Nothing to flag. Whatever a surface uses for ordinary text. */
    NEUTRAL,

    /** It worked, it is current, it came back. */
    GOOD,

    /** It failed. The one tone that has to be findable in a list of forty lines. */
    BAD,

    /** Not a failure, but not what was asked for either - stopped, too late, still waiting. */
    WARN,

    /**
     * Supporting detail under a line that carries the news.
     *
     * <p>The artefact versions under a service, and a service that is not moving at all. Present so
     * that a report reads as a few lines with detail under them rather than as forty equal ones.</p>
     */
    MUTED
}
