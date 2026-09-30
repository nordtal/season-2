package eu.nordtal.s2.database.inbox;

/** Re-reads the settings and the message bundles a process can re-read while it runs; one kind for every server. */
public record Reload() implements SmpRequest, HungerGamesRequest, LimboRequest, ProxyRequest {}
