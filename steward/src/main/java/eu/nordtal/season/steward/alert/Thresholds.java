package eu.nordtal.season.steward.alert;

/** The three numbers the measured alerts fire on, from the web group's {@code alerts}. */
public record Thresholds(int diskPercent, int memoryPercent, int backupAgeHours) {}
