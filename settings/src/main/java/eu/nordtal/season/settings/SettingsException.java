package eu.nordtal.season.settings;

import java.io.Serial;

/** Signals a group of settings that could not be read or that its check refused. */
public final class SettingsException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public SettingsException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /** Creates the exception for a refusal recorded earlier, of which only the message is left. */
    public SettingsException(final String message) {
        super(message);
    }
}
