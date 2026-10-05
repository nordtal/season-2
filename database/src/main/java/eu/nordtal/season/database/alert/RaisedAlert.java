package eu.nordtal.season.database.alert;

import java.time.Instant;

/**
 * One row of {@code admin_alert}.
 *
 * @param raisedBy the module that raised it, such as {@code discord-bot}
 */
public record RaisedAlert(long id, Instant raised, String raisedBy, Alert alert) {}
