package eu.nordtal.season.stewardagent.bundles;

import java.nio.file.Path;

/**
 * One jar of ours on one service, found as the descriptors find it: beside the service's data, else in its image.
 *
 * @param ownImage whether it is the service's own jar from its image rather than a plugin beside its data
 * @param followsMessages whether its descriptor says the process follows the message overrides
 */
public record ServiceJar(String service, Path path, boolean ownImage, boolean followsMessages) {}
