package eu.nordtal.season.database.alert;

import eu.nordtal.season.common.id.Actor;
import java.time.Instant;
import java.util.UUID;

/**
 * An alert as the journal holds it.
 *
 * @param id its journal entry
 * @param raisedBy steward or the bot
 */
public record RaisedAlert(UUID id, Instant raised, Actor raisedBy, Alert alert) {}
