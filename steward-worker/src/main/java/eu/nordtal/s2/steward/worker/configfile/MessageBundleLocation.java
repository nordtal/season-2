package eu.nordtal.s2.steward.worker.configfile;

import java.nio.file.Path;

/**
 * One message bundle found under the mount, before its jar has been opened.
 *
 * <p>A "bundle" here is one operator override directory - {@code plugins/<module>/messages/} on a
 * Paper or Velocity server, or {@code messages/} directly under a standalone jar's own config
 * directory (steward/48). It stands for every {@code messages/<root>/{en,de}.properties} resource
 * packaged inside {@link #jar()}, merged the same way {@code eu.nordtal.s2.common.message.Messages}
 * merges them at runtime - several roots layered into one flat key space, because that override
 * directory holds exactly one {@code en.properties} and one {@code de.properties} for however many
 * roots the module actually loads.</p>
 *
 * @param service           the compose service directory this bundle lives under - {@code smp},
 *                          {@code discord-bot}
 * @param module            the plugin's own data directory under the service, e.g. {@code smp} for
 *                          {@code /configs/smp/smp/messages}, or the empty string when the override
 *                          directory sits directly under the service - a standalone jar such as
 *                          {@code discord-bot} has no {@code plugins/} layer above its own data
 * @param jar               the jar this bundle's packaged (English and German) text lives in - found
 *                          by matching {@link JarName#prefixOf} against {@link #module()} (or
 *                          {@link #service()} when {@code module} is empty) among the jars sitting
 *                          directly in the service's own directory, on the configs mount first and
 *                          the volumes mount second (discord-bot's jar is not under the configs
 *                          mount at all - see {@code eu.nordtal.s2.steward.worker.config.StewardSpec#volumesRoot})
 * @param overrideDirectory where {@code en.properties} and {@code de.properties} are read from and
 *                          written to - {@code eu.nordtal.s2.common.message.Messages} creates this
 *                          directory (with its {@code README.txt}) the first time the module starts,
 *                          whether or not an operator has ever used it
 * @param writable          whether this process can actually save a change - the directory has to be
 *                          writable, because a save is a create-or-replace of one whole file in it
 */
public record MessageBundleLocation(
        String service, String module, Path jar, Path overrideDirectory, boolean writable) {}
