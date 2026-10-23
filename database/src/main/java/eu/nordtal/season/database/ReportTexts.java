package eu.nordtal.season.database;

import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.math.BigDecimal;

/** What the resolver finds, and a size in bytes. */
@Name("Resolver findings")
public interface ReportTexts {

    @Name("Words as they came: a subsystem's answer")
    MessageRef words(@Arg("text") @Example("The End opens tonight at eight.") String text);

    @Name("A size in bytes")
    MessageRef size(@Arg("amount") @Example("1.2") BigDecimal amount, @Arg("unit") ByteSize.Unit unit);

    @Name("The latest release unread")
    MessageRef releaseUnread(
            @Arg("repo") @Example("nordtal/season-2") String repo,
            @Arg("error") @Example("HTTP 403 rate limit exceeded") String error);

    @Name("A release without a jar")
    MessageRef releaseWithoutJar(
            @Arg("release") @Example("0.57.0") String release, @Arg("artefact") @Example("smp") String artefact);

    @Name("A release's jar without its digest")
    MessageRef releaseJarWithoutDigest(
            @Arg("release") @Example("0.57.0") String release, @Arg("jar") @Example("smp-0.57.0.jar") String jar);

    @Name("A release without a pack")
    MessageRef releaseWithoutPack(@Arg("release") @Example("0.57.0") String release);

    @Name("A release's pack without its SHA-1")
    MessageRef releaseWithoutSha1(
            @Arg("release") @Example("0.57.0") String release,
            @Arg("zip") @Example("nordtal-pack-0.57.0.zip") String zip);

    @Name("A pack's SHA-1 unread")
    MessageRef sha1Unread(
            @Arg("file") @Example("nordtal-pack-0.57.0.zip.sha1") String file,
            @Arg("error") @Example("No such file") String error);

    @Name("No build for this Minecraft version")
    MessageRef noBuild(
            @Arg("artefact") @Example("chunky") String artefact,
            @Arg("minecraft") @Example("1.21.10") String minecraft,
            @Arg("loader") @Example("paper") String loader);

    @Name("No source answered")
    MessageRef noSource(@Arg("artefact") @Example("chunky") String artefact);

    @Name("A volume not mounted")
    MessageRef notMounted(@Arg("directory") @Example("/data/plugins") String directory);

    @Name("No database to read the proxy's pack from")
    MessageRef noDatabase();

    @Name("The proxy's pack unread")
    MessageRef proxyPackUnread(@Arg("error") @Example("Connection refused") String error);

    @Name("The proxy has no pack")
    MessageRef proxyWithoutPack();

    @Name("The proxy's pack has no SHA-1")
    MessageRef proxyPackWithoutSha1();

    @Name("Velocity ahead of the proxy's API")
    MessageRef velocityAhead(
            @Arg("version") @Example("3.5.0") String version, @Arg("api") @Example("3.4.0") String api);
}
