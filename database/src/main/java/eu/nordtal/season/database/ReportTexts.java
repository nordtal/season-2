package eu.nordtal.season.database;

import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import java.math.BigDecimal;

/** What the resolver finds, and a size in bytes. */
@Name("Resolver findings")
public interface ReportTexts {

    @Name("Words as they came: a subsystem's answer")
    MessageRef words(@Arg("text") String text);

    @Name("A size in bytes")
    MessageRef size(@Arg("amount") BigDecimal amount, @Arg("unit") ByteSize.Unit unit);

    @Name("The latest release unread")
    MessageRef releaseUnread(@Arg("repo") String repo, @Arg("error") String error);

    @Name("A release without a jar")
    MessageRef releaseWithoutJar(@Arg("release") String release, @Arg("artefact") String artefact);

    @Name("A release's jar without its digest")
    MessageRef releaseJarWithoutDigest(@Arg("release") String release, @Arg("jar") String jar);

    @Name("A release without a pack")
    MessageRef releaseWithoutPack(@Arg("release") String release);

    @Name("A release's pack without its SHA-1")
    MessageRef releaseWithoutSha1(@Arg("release") String release, @Arg("zip") String zip);

    @Name("A pack's SHA-1 unread")
    MessageRef sha1Unread(@Arg("file") String file, @Arg("error") String error);

    @Name("No build for this Minecraft version")
    MessageRef noBuild(
            @Arg("artefact") String artefact, @Arg("minecraft") String minecraft, @Arg("loader") String loader);

    @Name("No source answered")
    MessageRef noSource(@Arg("artefact") String artefact);

    @Name("A volume not mounted")
    MessageRef notMounted(@Arg("directory") String directory);

    @Name("No database to read the proxy's pack from")
    MessageRef noDatabase();

    @Name("The proxy's pack unread")
    MessageRef proxyPackUnread(@Arg("error") String error);

    @Name("The proxy has no pack")
    MessageRef proxyWithoutPack();

    @Name("The proxy's pack has no SHA-1")
    MessageRef proxyPackWithoutSha1();

    @Name("Velocity ahead of the proxy's API")
    MessageRef velocityAhead(@Arg("version") String version, @Arg("api") String api);
}
