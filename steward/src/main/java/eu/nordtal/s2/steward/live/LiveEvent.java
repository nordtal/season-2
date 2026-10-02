package eu.nordtal.s2.steward.live;

/**
 * One event of {@code /api/live}: a topic now reads differently, and the version it reads at.
 *
 * Never the data itself: the browser asks the route that owns it, so every answer is shaped in one place.
 */
public record LiveEvent(Topic topic, String version) {}
