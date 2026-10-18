package eu.nordtal.season.steward.alert;

public record Thresholds(int diskPercent, int memoryPercent, int backupAgeHours) {}
