package eu.nordtal.s2.commands;

/**
 * What every process provides, whatever the command: somewhere to wait and somewhere to fail.
 *
 * On a command inbox {@code async} must run inline, which {@code CommandInbox#register} checks.
 */
public interface CommandEffects {

    /** Runs the part that waits, somewhere it is allowed to wait; inline on an inbox. */
    void async(Runnable work);

    /** Reports a failure the way this process reports failures; the user is told separately. */
    void warn(String what, Throwable failure);
}
