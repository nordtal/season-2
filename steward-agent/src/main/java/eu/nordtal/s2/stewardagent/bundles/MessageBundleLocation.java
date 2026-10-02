package eu.nordtal.s2.stewardagent.bundles;

import java.nio.file.Path;

/**
 * One override directory under the mount, standing for every bundle its jar packages, before the jar is opened.
 *
 * @param service the compose service directory it lives under
 * @param module the plugin's data directory under the service, or the empty string for a standalone jar
 * @param jar the jar holding the packaged text, found by its name prefix in configs, then volumes
 * @param overrideDirectory where {@code en.properties} and {@code de.properties} are read and written
 * @param writable whether a save can be written, which needs the directory writable
 */
public record MessageBundleLocation(
        String service, String module, Path jar, Path overrideDirectory, boolean writable) {}
