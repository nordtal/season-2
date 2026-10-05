package eu.nordtal.season.stewardagent.bundles;

import java.nio.file.Path;

/**
 * One jar that packages message bundles, before it is opened.
 *
 * @param service the compose service directory it lives under
 * @param module the plugin's name as its jar is named, or the empty string for the service's own jar
 * @param jar the jar holding the packaged texts
 */
public record MessageBundleLocation(String service, String module, Path jar) {}
