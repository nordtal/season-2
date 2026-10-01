package eu.nordtal.s2.settings;

import java.io.Serial;

/** A group of settings that could not be read, or whose check refused it; the message names which and why. */
public final class SettingsException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public SettingsException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /** A refusal recorded earlier, of which only the sentence is left. */
    public SettingsException(final String message) {
        super(message);
    }
}
