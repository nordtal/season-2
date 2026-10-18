package eu.nordtal.season.steward.live;

/** One event of {@code /api/live}: a topic and its new version. It holds no data. */
public record LiveEvent(Topic topic, String version) {}
